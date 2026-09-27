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

tasks.named<Test>("jvmTest") {
    // THE IN-PROCESS SUITES STAND IN FOR A SERVICE THAT STARTED CORRECTLY. Ktor fixes its JVM shutdown
    // hook switch on the first `start()` in the process, `testApplication` starts servers, and
    // `EngineDrain` refuses to be built beside a hook that is on (#90) — so without this the drain's
    // tests would pass or fail by which suite the runner happened to take first.
    systemProperty("io.ktor.server.engine.ShutdownHook", "false")
    // The one suite that must meet Ktor's default spawns a JVM of its own on this classpath, without
    // the property above. See KtorShutdownHookJvmTest.
    doFirst { systemProperty("kore.test.classpath", classpath.asPath) }
}
