package io.github.youndie.kore.sample

/** The JVM entry point. The only thing that differs between the two builds. */
public fun main(args: Array<String>) {
    println(buildLine())
    val options = SampleOptions.parse(args)
    if (options.kore) startKoreSample(options) else startSample(options)
}
