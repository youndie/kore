plugins {
    alias(wip.plugins.kotlinMultiplatform)
}

kotlin {
    explicitApi()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.ktor.server.test.host)
            implementation(libs.kotlinx.coroutines.test)
        }

        commonMain.dependencies {
            // Not `kore-core`: nothing here orders a shutdown or reads a probe. A module that
            // depends on what it does not call makes the next reader look for the call.
            api(libs.ktor.server.core)
            // `api`: the call takes Koin's own `KoinApplication` declaration and hands back its `Koin`,
            // so both are part of this module's surface. `koin-ktor` for `setKoin` and for the check
            // that its plugin is not installed alongside — never for the plugin itself.
            api(libs.koin.core)
            api(libs.koin.ktor)
        }
    }
}
