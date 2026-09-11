plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // A library, so every public symbol is a deliberate one. Without this the default visibility is
    // public and the surface grows by accident — which for a library is the one kind of growth that
    // cannot be taken back in a patch release.
    explicitApi()

    sourceSets {
        commonMain.dependencies {
            // `api` rather than `implementation`: a participant's contract is a suspending function
            // and the stage machine hands back coroutine types, so coroutines are part of kore's
            // surface. Declared as `implementation` they land in the POM at runtime scope and a
            // consumer cannot compile against the API at all.
            api(libs.kotlinx.coroutines.core)
        }
    }
}
