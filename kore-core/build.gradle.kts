plugins {
    alias(wip.plugins.kotlinMultiplatform)
}

kotlin {
    // A library, so every public symbol is a deliberate one. Without this the default visibility is
    // public and the surface grows by accident — which for a library is the one kind of growth that
    // cannot be taken back in a patch release.
    explicitApi()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }

        commonMain.dependencies {
            // `api` rather than `implementation`: a participant's contract is a suspending function
            // and the stage machine hands back coroutine types, so coroutines are part of kore's
            // surface. Declared as `implementation` they land in the POM at runtime scope and a
            // consumer cannot compile against the API at all.
            api(libs.kotlinx.coroutines.core)
        }
    }
}

// THE SIGNAL HANDLER IS C, on every native target (B-64). A handler written in Kotlin is a C-to-Kotlin
// bridge that initialises the runtime on a thread without one — and the kernel may pick a worker thread
// in its first instructions. `src/nativeInterop/cinterop/koreSignal.def` carries the C inline.
kotlin.targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
    compilations.getByName("main").cinterops.create("koreSignal")
}
