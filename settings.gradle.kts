pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { mavenLocal(); mavenCentral() }
}
rootProject.name = "http3-build"
// com.netonstream:http3 (neton.http.h3): HTTP/3 over com.netonstream:quic. SPEC §1.
include(":http3")

// Interop peers and scripts (SPEC §6); not published.
include(":http3-interop")

// com.netonstream:http from the sibling repo until it is published.
includeBuild("../http")
