package io.github.youndie.kore.config

/** `System.getenv()` returns the whole map, so listing is free here. */
public actual fun systemEnvironment(): Environment =
    object : Environment {
        override fun lookup(name: String): String? = System.getenv(name)

        override fun names(): EnvironmentNames = EnvironmentNames.Listed(System.getenv().keys)
    }
