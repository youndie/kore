package io.github.youndie.kore.ktor

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

@OptIn(ExperimentalForeignApi::class)
internal actual fun freePort(): Int =
    memScoped {
        val descriptor = socket(AF_INET, SOCK_STREAM, 0)
        check(descriptor >= 0) { "socket failed" }
        try {
            val address = alloc<sockaddr_in>()
            address.sin_family = AF_INET.convert()
            check(bind(descriptor, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind failed" }
            val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            check(getsockname(descriptor, address.ptr.reinterpret(), length.ptr) == 0) { "getsockname failed" }
            // Network byte order, swapped by hand: `ntohs` is a macro on Darwin and is not in its
            // `platform.posix`. Every native target kore builds is little-endian.
            val raw = address.sin_port.toInt()
            ((raw and 0xff) shl 8) or (raw shr 8)
        } finally {
            close(descriptor)
        }
    }
