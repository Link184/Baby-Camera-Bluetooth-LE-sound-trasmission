package com.link184.babycamera.security

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * The link's cryptography on Android. BouncyCastle rather than the system provider, because X25519
 * only arrived in Android's own provider in recent versions, and BabyCamera runs from Android 10.
 */
fun androidNoiseCrypto(): NoiseCrypto = NoiseCrypto(CryptographyProvider.JDK(BouncyCastleProvider()))
