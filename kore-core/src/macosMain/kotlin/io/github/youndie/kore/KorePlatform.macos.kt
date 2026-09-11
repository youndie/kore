package io.github.youndie.kore

/**
 * The one target where a kore promise is unavailable. Lookup by name works; listing does not.
 *
 * This is a development target — nothing is deployed to it — so the gap is acceptable. What is not
 * acceptable is reporting "no unknown variables found" here, which is why the flag is false rather
 * than the check being quietly skipped.
 */
public actual fun korePlatform(): KorePlatform = KorePlatform("macos", canEnumerateEnvironment = false)
