plugins {
    alias(wip.plugins.kotlinMultiplatform)
}

kotlin {
    explicitApi()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.ktor.server.test.host)
            // The engine, for the drain participant's tests only. kore-ktor itself takes whatever
            // EmbeddedServer it is handed and depends on no engine.
            implementation(libs.ktor.server.cio)
            implementation(libs.kotlinx.coroutines.test)
        }

        commonMain.dependencies {
            api(project(":kore-core"))
            // `ktor-server-core` and nothing else. The engine is the consumer's choice: kore wraps
            // whatever `EmbeddedServer` it is handed, and depending on CIO here would put an engine
            // in the classpath of every service that only wanted the routes.
            api(libs.ktor.server.core)
        }

        jvmMain.dependencies {
            // Not an engine: the socket library CIO binds with, so `requireListenable` fails where the
            // engine would and for the same reason (B-59). JVM only — its native server socket closes
            // its descriptor later, on the selector thread, so the native half binds through posix.
            implementation(libs.ktor.network)
        }
    }
}
