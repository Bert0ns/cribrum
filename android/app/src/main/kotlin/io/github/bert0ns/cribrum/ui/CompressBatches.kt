// What the compress screen asks of the background batch job (docs/design.md §9), so a test can stand in for it.
package io.github.bert0ns.cribrum.ui

import android.content.Context
import io.github.bert0ns.cribrum.compress.BatchResult
import io.github.bert0ns.cribrum.compress.BatchState
import io.github.bert0ns.cribrum.compress.CompressBatch
import io.github.bert0ns.cribrum.compress.CompressionLevel
import io.github.bert0ns.cribrum.model.ImageRef
import kotlinx.coroutines.flow.StateFlow

interface CompressBatches {
    val state: StateFlow<BatchState>

    /** Compresses the photos at [paths], one at a time, at [level]. False, and nothing starts, while a batch waits or runs. */
    fun start(paths: List<String>, level: CompressionLevel): Boolean

    /** Stops after the photo in progress. What is done stays compressed. */
    fun cancel()

    /** Puts the originals of [result] back from the trash, and returns those that stayed there. */
    suspend fun undo(result: BatchResult): List<ImageRef>
}

/** The app's one batch job. */
class DeviceBatches(context: Context) : CompressBatches {
    private val context = context.applicationContext
    override val state: StateFlow<BatchState> get() = CompressBatch.state

    override fun start(paths: List<String>, level: CompressionLevel) = CompressBatch.start(context, paths, level)

    override fun cancel() = CompressBatch.cancel(context)

    override suspend fun undo(result: BatchResult) = CompressBatch.undo(context, result)
}
