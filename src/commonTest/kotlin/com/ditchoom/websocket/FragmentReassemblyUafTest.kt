package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for the fragmented-message use-after-free unmasked by the
 * `deterministic()` library default (websocket #19).
 *
 * ## The bug
 * [com.ditchoom.websocket.frame.MessageAssembler] used to retain each fragment's raw
 * wire buffer — a *slice* over a frame-stream chunk — and copy them all lazily in
 * `combineBuffers()` only once the final fragment arrived. But buffers handed out by
 * `DefaultStreamProcessor.readBuffer` alias pooled/native chunk memory, and a *later*
 * read can free the parent chunk: when a subsequent frame spans a chunk boundary, the
 * multi-chunk copy path drains and frees the earlier chunk (`removeChunkIfEmpty` →
 * `freeNativeMemory`) while an earlier fragment's slice still points into it. On
 * `deterministic()` (raw-malloc `NativeBuffer`) that `free()` really releases the memory,
 * so the deferred copy read freed memory and **segfaulted on linuxX64 / macOS native**.
 * `Default` (linux `ByteArrayBuffer`, GC-managed, no-op `freeNativeMemory`) masked it.
 *
 * ## The reproduction
 * The trigger is a chunk boundary that falls **inside a later frame while an earlier
 * fragment's slice is still live**. We deliver `Text(FIN=0)` + `Continuation(FIN=1)` +
 * `Close` across two socket reads split *inside the continuation frame*: read 1 carries the
 * whole first fragment plus the head of the continuation; read 2 carries the tail of the
 * continuation plus the close.
 *
 * Both reads are under the 4096-byte compaction threshold, so the connection's refill
 * copies each into a fresh `deterministic()` (`NativeBuffer`) chunk. Reading the first
 * fragment returns a slice of read 1's chunk; reading the continuation then spans read 1 →
 * read 2, so `readBuffer`'s multi-chunk copy path drains and frees read 1's chunk — while
 * the first fragment's slice is still queued for reassembly. On the old lazy-copy assembler
 * that dangles the slice and the final combine reads freed native memory (segfault on
 * native). With the eager-copy fix the first fragment's bytes are captured at arrival, so
 * the later free is harmless.
 */
class FragmentReassemblyUafTest {
    @Test
    fun fragmentSurvivesParentChunkFreedByLaterFrameRead() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            // deterministic() is the library default and the allocator whose real free()
            // exposes the UAF; the connection's refill compacts each sub-threshold read
            // into a deterministic chunk.
            val connection =
                MockAutobahnHelpers.connectWithHandshake(transport, bufferFactory = BufferFactory.deterministic())

            // Sized so each split read stays under COMPACT_READ_THRESHOLD (4096) and is
            // therefore compacted into a deterministic NativeBuffer chunk.
            val payloadA = "A".repeat(1000)
            val payloadB = "B".repeat(1000)

            val f1 = MockAutobahnHelpers.buildServerTextFrame(payloadA, fin = false).toBytes()
            val payloadBBuf =
                BufferFactory.Default.allocate(payloadB.length).apply {
                    writeString(payloadB, Charset.UTF8)
                    resetForRead()
                }
            val f2 = MockAutobahnHelpers.buildServerContinuationFrame(payloadBBuf, fin = true).toBytes()
            val close = MockAutobahnHelpers.buildServerCloseFrame(1000u).toBytes()

            val all = f1 + f2 + close
            // Split inside the continuation frame: read 1 = [F1][first half of F2],
            // read 2 = [second half of F2][Close].
            val splitAt = f1.size + f2.size / 2
            enqueueDefault(transport, all.copyOfRange(0, splitAt))
            enqueueDefault(transport, all.copyOfRange(splitAt, all.size))

            val msg =
                withTimeout(5.seconds) {
                    connection.receive().first { it is WebSocketMessage.Text }
                }
            assertIs<WebSocketMessage.Text>(msg)
            assertEquals(payloadA + payloadB, msg.payload)
            connection.close()
        }

    /**
     * Enqueues [bytes] as a single Default-backed socket read. The buffer is handed off with
     * its position at the end of the written data (not flipped to read mode): the mock
     * transport's `read()` calls `resetForRead()` itself, so pre-flipping here would set the
     * read limit to 0 and deliver an empty read.
     */
    private fun enqueueDefault(
        transport: MockWebSocketTransport,
        bytes: ByteArray,
    ) {
        val buffer = BufferFactory.Default.allocate(bytes.size)
        buffer.writeBytes(bytes)
        transport.enqueueRead(buffer)
    }

    private fun ReadBuffer.toBytes(): ByteArray {
        resetForRead()
        return ByteArray(remaining()) { readByte() }
    }
}
