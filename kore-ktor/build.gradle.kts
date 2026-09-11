plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()

    sourceSets {
        commonMain.dependencies {
            api(project(":kore-core"))
            // `ktor-server-core` and nothing else. The engine is the consumer's choice: kore wraps
            // whatever `EmbeddedServer` it is handed, and depending on CIO here would put an engine
            // in the classpath of every service that only wanted the routes.
            api(libs.ktor.server.core)
        }
    }
}
