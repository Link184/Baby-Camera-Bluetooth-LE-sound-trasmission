package com.link184.babycamera.security

/** The platform's real cryptography, as the app uses it. */
expect val testCrypto: NoiseCrypto

@OptIn(ExperimentalStdlibApi::class)
fun hex(text: String): ByteArray = text.hexToByteArray()
