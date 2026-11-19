package com.link184.babycamera.security

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit

actual val testCrypto: NoiseCrypto = NoiseCrypto(CryptographyProvider.CryptoKit)
