import CoreBluetooth
import Foundation
import Shared

/// Must match `BLE_SERVICE_UUID` in the shared Kotlin code.
let babyCamServiceUUID = CBUUID(string: "6B1E0001-3C4F-4D8E-9A57-B5C2A1D0E7F3")
/// Readable characteristic holding the host's L2CAP channel number, 2 bytes little-endian.
let psmCharacteristicUUID = CBUUID(string: CBUUIDL2CAPPSMCharacteristicString)

/// Swift side of the shared `PeerLink`, over Core Bluetooth.
///
/// Host: publishes an L2CAP channel, exposes its PSM in a GATT characteristic, and advertises
/// until `maxPeers` joiners have opened a channel. Join: scans, connects, reads the PSM, and opens
/// the L2CAP channel. Everything runs on the main thread (Core Bluetooth queue `nil`, streams on
/// the main run loop).
///
/// In an Android + iPhone pair the iPhone should join: Android phones usually can't see an
/// iPhone that advertises from the background.
final class BleBridge: NSObject, IosBleBridge {
    /// One open L2CAP channel. `id` is new for every connection.
    private final class Connection {
        let id: Int32
        let channel: CBL2CAPChannel
        var outBuffer = Data()

        init(id: Int32, channel: CBL2CAPChannel) {
            self.id = id
            self.channel = channel
        }
    }

    private var listener: IosBleListener?
    private var isHost = false
    private var maxPeers = 1
    private var central: CBCentralManager?
    private var peripheralManager: CBPeripheralManager?
    private var peripheral: CBPeripheral?
    private var publishedPSM: CBL2CAPPSM?
    private var connections: [Int32: Connection] = [:]
    private var nextPeerId: Int32 = 1
    private var rssiTimer: Timer?

    func start(host: Bool, maxPeers: Int32, listener: IosBleListener) {
        stop()
        self.listener = listener
        isHost = host
        self.maxPeers = max(1, Int(maxPeers))
        listener.onWorking(step: "Waiting for Bluetooth")
        if host {
            peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
        } else {
            central = CBCentralManager(delegate: self, queue: nil)
        }
    }

    func send(peer: Int32, data: Data) {
        guard let connection = connections[peer] else { return }
        connection.outBuffer.append(data)
        flush(connection)
    }

    func pendingBytes(peer: Int32) -> Int64 {
        Int64(connections[peer]?.outBuffer.count ?? 0)
    }

    func disconnect(peer: Int32) {
        guard let connection = connections[peer] else { return }
        channelEnded(connection, reason: "Disconnected by the app")
    }

    func stop() {
        rssiTimer?.invalidate()
        rssiTimer = nil
        for connection in Array(connections.values) { closeStreams(connection) }
        connections = [:]
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

    // MARK: Data channels

    private func openChannel(_ opened: CBL2CAPChannel?, _ error: Error?) {
        guard let opened, error == nil else {
            let reason = "L2CAP channel failed: \(error?.localizedDescription ?? "unknown error")"
            // On a host, one joiner's failure must not cut off the others.
            if isHost && !connections.isEmpty { return log(reason) }
            return fail(reason)
        }
        let name = opened.peer.identifier.uuidString
        if isHost {
            // A phone that reconnects before its old channel timed out: the old one is dead.
            for old in connections.values where old.channel.peer.identifier == opened.peer.identifier {
                log("\(name) reconnected - closing its old channel")
                closeStreams(old)
                connections[old.id] = nil
                listener?.onPeerDisconnected(peer: old.id)
            }
            if connections.count >= maxPeers {
                opened.inputStream.close()
                opened.outputStream.close()
                return log("Refusing \(name): already \(maxPeers) listeners")
            }
        }
        let connection = Connection(id: nextPeerId, channel: opened)
        nextPeerId += 1
        connections[connection.id] = connection
        for stream in [opened.inputStream as Stream, opened.outputStream as Stream] {
            stream.delegate = self
            stream.schedule(in: .main, forMode: .common)
            stream.open()
        }
        log("L2CAP channel open to \(name) (\(connections.count) connected)")
        listener?.onPeerConnected(peer: connection.id, name: name)
        updateAdvertising()
    }

    private func closeStreams(_ connection: Connection) {
        for stream in [connection.channel.inputStream as Stream, connection.channel.outputStream as Stream] {
            stream.delegate = nil
            stream.close()
            stream.remove(from: .main, forMode: .common)
        }
    }

    /// A channel closed or broke. A joiner has nothing left; a host keeps serving the others.
    private func channelEnded(_ connection: Connection, reason: String) {
        guard connections[connection.id] != nil else { return }
        guard isHost else { return fail(reason) }
        closeStreams(connection)
        connections[connection.id] = nil
        log("\(connection.channel.peer.identifier.uuidString) disconnected: \(reason) (\(connections.count) still connected)")
        listener?.onPeerDisconnected(peer: connection.id)
        updateAdvertising()
    }

    private func findConnection(for stream: Stream) -> Connection? {
        connections.values.first { $0.channel.inputStream === stream || $0.channel.outputStream === stream }
    }

    private func readAvailable(_ connection: Connection) {
        let input = connection.channel.inputStream!
        var buffer = [UInt8](repeating: 0, count: 4096)
        while input.hasBytesAvailable {
            let read = input.read(&buffer, maxLength: buffer.count)
            if read <= 0 { break }
            listener?.onData(peer: connection.id, data: Data(buffer[0..<read]))
        }
    }

    private func flush(_ connection: Connection) {
        guard let output = connection.channel.outputStream else { return }
        while output.hasSpaceAvailable && !connection.outBuffer.isEmpty {
            let count = connection.outBuffer.count
            let written = connection.outBuffer.withUnsafeBytes { raw in
                output.write(raw.bindMemory(to: UInt8.self).baseAddress!, maxLength: count)
            }
            if written <= 0 { break }
            connection.outBuffer = connection.outBuffer.subdata(in: written..<count)
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
    /// Advertises while there is room for another listener, and stops when full.
    private func updateAdvertising() {
        guard isHost, let peripheralManager, peripheralManager.state == .poweredOn, publishedPSM != nil else { return }
        if connections.count >= maxPeers {
            if peripheralManager.isAdvertising {
                log("\(maxPeers) listeners connected - not accepting more")
                peripheralManager.stopAdvertising()
            }
        } else if !peripheralManager.isAdvertising {
            peripheralManager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [babyCamServiceUUID]])
        }
    }

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
        updateAdvertising()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error {
            let reason = "Advertising failed: \(error.localizedDescription)"
            // Listeners already connected keep listening.
            return connections.isEmpty ? fail(reason) : log(reason)
        }
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
        guard let connection = findConnection(for: aStream) else { return }
        switch eventCode {
        case .hasBytesAvailable: readAvailable(connection)
        case .hasSpaceAvailable: flush(connection)
        case .errorOccurred:
            channelEnded(connection, reason: "Data channel error: \(aStream.streamError?.localizedDescription ?? "unknown")")
        case .endEncountered: channelEnded(connection, reason: "Data channel closed")
        default: break
        }
    }
}
