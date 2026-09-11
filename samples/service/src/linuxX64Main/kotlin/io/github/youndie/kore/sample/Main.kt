package io.github.youndie.kore.sample

/** The Kotlin/Native entry point. Named in `build.gradle.kts` as `entryPoint`. */
public fun main(args: Array<String>) {
    val options = SampleOptions.parse(args)
    if (options.kore) startKoreSample(options) else startSample(options)
}
