import CoreBluetooth
import Foundation
import Shared

/// Must match `BLE_SERVICE_UUID` in the shared Kotlin code.
let babyCamServiceUUID = CBUUID(string: "6B1E0001-3C4F-4D8E-9A57-B5C2A1D0E7F3")
/// Readable characteristic holding the host's L2CAP channel number, 2 bytes little-endian.
let psmCharacteristicUUID = CBUUID(string: CBUUIDL2CAPPSMCharacteristicString)

/// Swift side of the shared `PeerLink`, over Core Bluetooth.
///
/// Host: publishes an L2CAP channel, exposes its PSM in a GATT characteristic, and advertises.
/// Join: scans, connects, reads the PSM, and opens the L2CAP channel. Everything runs on the
/// main thread (Core Bluetooth queue `nil`, streams on the main run loop).
///
/// In an Android + iPhone pair the iPhone should join: Android phones usually can't see an
/// iPhone that advertises from the background.
final class BleBridge: NSObject, IosBleBridge {
    private var listener: IosBleListener?
    private var central: CBCentralManager?
    private var peripheralManager: CBPeripheralManager?
    private var peripheral: CBPeripheral?
    private var publishedPSM: CBL2CAPPSM?
    private var channel: CBL2CAPChannel?
    private var outBuffer = Data()
    private var rssiTimer: Timer?

    func start(host: Bool, listener: IosBleListener) {
        stop()
        self.listener = listener
        listener.onWorking(step: "Waiting for Bluetooth")
        if host {
            peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
        } else {
            central = CBCentralManager(delegate: self, queue: nil)
        }
    }

    func send(data: Data) {
        outBuffer.append(data)
        flush()
    }

    func pendingBytes() -> Int64 {
        Int64(outBuffer.count)
    }

    func stop() {
        rssiTimer?.invalidate()
        rssiTimer = nil
        closeChannel()
        if let central {
            central.delegate = nil
            if central.state == .poweredOn {
                central.stopScan()
                if let peripheral { central.cancelPeripheralConnection(peripheral) }
            }
        }
        if let peripheralManager {
            peripheralManager.delegate = nil
            if peripheralManager.state == .poweredOn {
                peripheralManager.stopAdvertising()
                peripheralManager.removeAllServices()
                if let publishedPSM { peripheralManager.unpublishL2CAPChannel(publishedPSM) }
            }
        }
        central = nil
        peripheralManager = nil
        peripheral = nil
        publishedPSM = nil
        outBuffer = Data()
        listener = nil
    }

    private func log(_ message: String) {
        listener?.onLog(message: message)
    }

    private func fail(_ reason: String) {
        let current = listener
        stop()
        current?.onFailed(reason: reason)
    }

    /// Returns a failure reason for Bluetooth states that can't be used, nil when usable or pending.
    private func unusable(_ state: CBManagerState) -> String? {
        switch state {
        case .unauthorized: "Bluetooth permission denied (Settings > Privacy > Bluetooth)"
        case .poweredOff: "Bluetooth is off"
        case .unsupported: "This device has no Bluetooth LE"
        default: nil
        }
    }

    // MARK: Data channel

    private func openChannel(_ opened: CBL2CAPChannel?, _ error: Error?) {
        guard let opened, error == nil else {
            fail("L2CAP channel failed: \(error?.localizedDescription ?? "unknown error")")
            return
        }
        closeChannel()
        channel = opened
        for stream in [opened.inputStream as Stream, opened.outputStream as Stream] {
            stream.delegate = self
            stream.schedule(in: .main, forMode: .common)
            stream.open()
        }
        peripheralManager?.stopAdvertising()
        log("L2CAP channel open to \(opened.peer.identifier.uuidString)")
        listener?.onConnected(peer: opened.peer.identifier.uuidString)
    }

    private func closeChannel() {
        guard let channel else { return }
        for stream in [channel.inputStream as Stream, channel.outputStream as Stream] {
            stream.delegate = nil
            stream.close()
            stream.remove(from: .main, forMode: .common)
        }
        self.channel = nil
    }

    private func readAvailable() {
        guard let input = channel?.inputStream else { return }
        var buffer = [UInt8](repeating: 0, count: 4096)
        while input.hasBytesAvailable {
            let read = input.read(&buffer, maxLength: buffer.count)
            if read <= 0 { break }
            listener?.onData(data: Data(buffer[0..<read]))
        }
    }

    private func flush() {
        guard let output = channel?.outputStream else { return }
        while output.hasSpaceAvailable && !outBuffer.isEmpty {
            let count = outBuffer.count
            let written = outBuffer.withUnsafeBytes { raw in
                output.write(raw.bindMemory(to: UInt8.self).baseAddress!, maxLength: count)
            }
            if written <= 0 { break }
            outBuffer = outBuffer.subdata(in: written..<count)
        }
    }
}

// MARK: Join (central)

extension BleBridge: CBCentralManagerDelegate, CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if let reason = unusable(central.state) { return fail(reason) }
        guard central.state == .poweredOn else { return }
        log("Scanning for the BabyCamera service")
        listener?.onWorking(step: "Scanning for a host")
        central.scanForPeripherals(withServices: [babyCamServiceUUID])
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard self.peripheral == nil else { return }
        central.stopScan()
        log("Found host \(peripheral.identifier.uuidString): signal \(RSSI) dBm")
        listener?.onRssi(rssi: RSSI.int32Value)
        self.peripheral = peripheral
        peripheral.delegate = self
        listener?.onWorking(step: "Connecting")
        central.connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        log("Connected")
        listener?.onWorking(step: "Reading the host's channel number")
        rssiTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.peripheral?.readRSSI()
        }
        peripheral.discoverServices([babyCamServiceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        fail("Connect failed: \(error?.localizedDescription ?? "unknown error")")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        fail("Bluetooth link lost: \(error?.localizedDescription ?? "disconnected")")
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == babyCamServiceUUID }) else {
            return fail("Host has no BabyCamera service")
        }
        peripheral.discoverCharacteristics([psmCharacteristicUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristic = service.characteristics?.first(where: { $0.uuid == psmCharacteristicUUID }) else {
            return fail("Host has no channel-number characteristic")
        }
        peripheral.readValue(for: characteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == psmCharacteristicUUID else { return }
        guard let value = characteristic.value, value.count >= 2, error == nil else {
            return fail("Reading the channel number failed: \(error?.localizedDescription ?? "bad value")")
        }
        let psm = CBL2CAPPSM(value[value.startIndex]) | (CBL2CAPPSM(value[value.startIndex + 1]) << 8)
        log("Host PSM=\(psm) - opening L2CAP channel")
        listener?.onWorking(step: "Opening the data channel")
        peripheral.openL2CAPChannel(psm)
    }

    func peripheral(_ peripheral: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: Error?) {
        openChannel(channel, error)
    }

    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        if error == nil { listener?.onRssi(rssi: RSSI.int32Value) }
    }
}

// MARK: Host (peripheral)

extension BleBridge: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if let reason = unusable(peripheral.state) { return fail(reason) }
        guard peripheral.state == .poweredOn else { return }
        listener?.onWorking(step: "Opening L2CAP channel")
        peripheral.publishL2CAPChannel(withEncryption: false)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didPublishL2CAPChannel PSM: CBL2CAPPSM, error: Error?) {
        if let error { return fail("Cannot open an L2CAP channel: \(error.localizedDescription)") }
        publishedPSM = PSM
        log("L2CAP channel listening, PSM=\(PSM)")
        let value = withUnsafeBytes(of: PSM.littleEndian) { Data($0) }
        let characteristic = CBMutableCharacteristic(
            type: psmCharacteristicUUID, properties: .read, value: value, permissions: .readable
        )
        let service = CBMutableService(type: babyCamServiceUUID, primary: true)
        service.characteristics = [characteristic]
        peripheral.add(service)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error { return fail("Adding the GATT service failed: \(error.localizedDescription)") }
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [babyCamServiceUUID]])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error { return fail("Advertising failed: \(error.localizedDescription)") }
        log("Advertising")
        listener?.onWorking(step: "Advertising - waiting for a joiner")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: Error?) {
        openChannel(channel, error)
    }
}

// MARK: Streams

extension BleBridge: StreamDelegate {
    func stream(_ aStream: Stream, handle eventCode: Stream.Event) {
        switch eventCode {
        case .hasBytesAvailable: readAvailable()
        case .hasSpaceAvailable: flush()
        case .errorOccurred: fail("Data channel error: \(aStream.streamError?.localizedDescription ?? "unknown")")
        case .endEncountered: fail("Data channel closed")
        default: break
        }
    }
}
