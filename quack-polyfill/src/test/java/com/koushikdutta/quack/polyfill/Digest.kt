package com.koushikdutta.quack.polyfill

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.buffers.ByteBufferList
import java.security.MessageDigest

suspend fun AsyncRead.digest(): String {
    val digest = MessageDigest.getInstance("MD5")
    val buffer = ByteBufferList()
    while (this(buffer)) {
        digest.update(buffer.readBytes())
    }

    return digest.digest().joinToString("") { "%02X".format(it).toLowerCase() }
}