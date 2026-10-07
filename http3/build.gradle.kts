plugins { kotlin("multiplatform") }

// com.netonstream:http3 (SPEC §1, §5): the targets com.netonstream:http and com.netonstream:quic both provide (quic,
// through openssl, has no 32-bit Android, and no Windows: io has no UDP there yet). The protocol core (frames, QPACK, stream state machines, the connection layer
// on the thin QUIC interface) is in commonMain and depends on com.netonstream:http only; the adapter onto
// com.netonstream:quic, whose driver is native, is in nativeMain.
kotlin {
    linuxX64(); linuxArm64()
    macosArm64(); macosX64()
    iosArm64(); iosSimulatorArm64(); iosX64()
    androidNativeArm64(); androidNativeX64()

    sourceSets {
        commonMain.dependencies { api("com.netonstream:http:0.1.1") }
        nativeMain.dependencies { api("com.netonstream:quic:0.1.0") }
        commonTest.dependencies { implementation(kotlin("test")) }
        // The TLS test double (MockTls) and the test PKI (TestPki) for the end-to-end tests over neton.quic, on the
        // double and on the real TLS session (SPEC §5 layers 3 and 4). Test-only: no production source set may depend
        // on quic-testkit.
        nativeTest.dependencies { implementation("com.netonstream:quic-testkit:0.1.0") }
    }
}
