pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "http3-build"
// com.netonstream:http3 (neton.http.h3): HTTP/3 over com.netonstream:quic. SPEC §1.
include(":http3")

// Interop peers and scripts (SPEC §6); not published.
include(":http3-interop")

// com.netonstream:quic and quic-testkit 0.1.0 from the sibling repo until quic 0.1.0 is on Maven Central; then drop this.
includeBuild("../quic")
