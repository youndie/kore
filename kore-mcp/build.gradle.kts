plugins {
    alias(wip.plugins.kotlinMultiplatform)
}

kotlin {
    explicitApi()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.ktor.server.test.host)
            // A real engine and real sockets for the path tests: a test client is free to normalise
            // `//mcp` before it leaves, and the request line CIO parses is the thing under test.
            implementation(libs.ktor.server.cio)
            // The application's own ContentNegotiation, which the SDK hands its answers to and which
            // changes them unless kore re-encodes first (B-68, B-69). Test-only: kore-mcp never installs it.
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        commonMain.dependencies {
            // Not `kore-core`: nothing here orders a shutdown or reads a probe. A module that depends
            // on what it does not call makes the next reader look for the call.
            api(libs.ktor.server.core)
            // `api`: the call takes the SDK's `Implementation` and `ServerCapabilities` and hands the
            // consumer its `Server` to register tools on, so the SDK is part of this module's surface.
            api(libs.mcp.server)
        }
    }
}
