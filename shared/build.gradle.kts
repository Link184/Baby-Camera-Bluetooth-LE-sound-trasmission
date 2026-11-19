plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidLibrary {
        namespace = "com.link184.babycamera.shared"
        compileSdk = 37
        minSdk = 28

        // Runs commonTest on this computer's JVM: ./gradlew :shared:testAndroidHostTest
        withHostTest {}
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kscan) // scans the baby unit's QR code
            implementation(libs.qrose) // draws it
            implementation(libs.cryptography.core) // the link encryption, see SecureLink
        }
        androidMain.dependencies {
            implementation(libs.cryptography.provider.jdk)
            // X25519 and ChaCha20-Poly1305 on every Android version, not only recent ones
            implementation(libs.bouncycastle)
        }
        iosMain.dependencies {
            implementation(libs.cryptography.provider.cryptokit)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
