package io.github.youndie.kore.sample

import kotlin.system.exitProcess

internal actual fun endProcess(code: Int): Nothing = exitProcess(code)
