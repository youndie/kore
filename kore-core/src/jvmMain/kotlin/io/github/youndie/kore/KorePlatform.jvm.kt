package io.github.youndie.kore

/** `System.getenv()` returns the whole map, so enumeration is free here. */
public actual fun korePlatform(): KorePlatform = KorePlatform("jvm", canEnumerateEnvironment = true)
