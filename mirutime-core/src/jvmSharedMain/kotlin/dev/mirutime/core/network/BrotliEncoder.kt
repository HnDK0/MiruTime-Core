package dev.mirutime.core.network

import io.ktor.util.ContentEncoder
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlin.coroutines.CoroutineContext
import org.brotli.dec.BrotliInputStream

// Ktor ContentEncoder for Brotli (br), backed by org.brotli:dec — a pure-Java
// decoder with no native libraries, the same one NoveLA's okhttp-brotli uses.
// That keeps one implementation across the jvm and android targets and removes
// the whole JNI/UnsatisfiedLinkError failure class.
//
// Decode-only: ContentEncoding is installed in DecompressResponse mode, so the
// encode paths below are unreachable. They fail loudly instead of emitting
// output we cannot actually produce.
internal actual val BrotliEncoder: ContentEncoder = object : ContentEncoder {
    override val name: String = "br"

    override fun decode(source: ByteReadChannel, coroutineContext: CoroutineContext): ByteReadChannel =
        BrotliInputStream(source.toInputStream()).toByteReadChannel(coroutineContext)

    override fun encode(source: ByteReadChannel, coroutineContext: CoroutineContext): ByteReadChannel =
        error("Brotli request compression is not supported (decode-only encoder)")

    override fun encode(source: ByteWriteChannel, coroutineContext: CoroutineContext): ByteWriteChannel =
        error("Brotli request compression is not supported (decode-only encoder)")
}
