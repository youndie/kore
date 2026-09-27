package io.github.youndie.kore.ktor

import java.net.ServerSocket

internal actual fun freePort(): Int = ServerSocket(0).use { it.localPort }
