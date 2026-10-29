// NEAR_DUPLICATE: resized, recompressed and edited copies (docs/design.md §5). Mirrors
// lab/src/cribrum_lab/detectors/near_duplicate.py.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.folders.isCameraFolder
import io.github.bert0ns.cribrum.folders.isMessagingFolder
import io.github.bert0ns.cribrum.hashing.isFlatHash
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.util.Locale

const val MAX_DISTANCE = 10

/**
 * Embedding similarity that still links two images. A 75% crop of a photo scores about
 * 0.90 with MobileCLIP-S0; different photos of the same party stay under 0.8. MobileCLIP2-S0
 * links about as many pairs at this threshold (docs/design.md, section 5).
 */
const val NEAR_DUPLICATE_MIN_COSINE = 0.90f

/**
 * Two images are linked when their perceptual hashes are within a Hamming distance, which catches
 * resized and recompressed copies such as WhatsApp's, or when their embeddings are close, which
 * also catches crops and edits. Linked images form a group. Recall first: both thresholds start loose.
 */
class NearDuplicateDetector : GroupDetector {
    override val id = "near_duplicate"
    override val version = 3
    override val category = Category.NEAR_DUPLICATE

    override suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>> {
        val members = mutableListOf<ImageRef>()
        val hashes = mutableListOf<ULong>()
        val vectors = mutableListOf<FloatArray?>()
        for (image in images) {
            val hash = try {
                context.perceptualHash(image)
            } catch (error: Exception) { // undecodable pixels: BitmapFactory returns null, files can vanish
                if (error is CancellationException) throw error
                continue
            }
            val vector = try {
                context.embedding(image)
            } catch (_: MissingEmbedding) { // a run without embeddings: hashes alone
                null
            }
            hashes += hash
            vectors += vector
            members += image
        }

        val links = hashLinks(hashes) + embeddingLinks(vectors)
        val result = LinkedHashMap<ImageRef, List<Signal>>()
        for (component in components(members.size, links)) {
            val prefs = component.map { preference(it, members[it], context) }
            val bestIdx = prefs.minWith(PREFERENCE_COMPARATOR).index
            val groupId = groupId(members[bestIdx].path)

            for (i in component) {
                val distance = (hashes[i] xor hashes[bestIdx]).countOneBits()
                val isBest = i == bestIdx
                val role = if (isBest) Role.BEST else Role.INFERIOR
                val evidence = mutableMapOf(
                    GROUP_KEY to groupId,
                    ROLE_KEY to role.value,
                    "distance" to distance.toString(),
                )
                if (isFlatHash(hashes[i])) evidence["flat"] = "true"
                // Each measure scores 1 for an identical copy and 0.5 at its linking threshold.
                val scores = mutableListOf(1.0 - distance / (2.0 * (MAX_DISTANCE + 1)))
                val vi = vectors[i]
                val vBest = vectors[bestIdx]
                if (vi != null && vBest != null) {
                    val cosine = dot(vi, vBest)
                    evidence["cosine"] = "%.3f".format(Locale.ROOT, cosine)
                    val margin = (cosine - NEAR_DUPLICATE_MIN_COSINE) / (1.0 - NEAR_DUPLICATE_MIN_COSINE)
                    scores += 0.5 + 0.5 * margin
                }
                val score = if (isBest) 0f else scores.max().toFloat().coerceIn(0f, 1f)
                result[members[i]] = listOf(Signal(id, version, Category.NEAR_DUPLICATE, score, evidence))
            }
        }
        return result
    }

    private suspend fun hashLinks(hashes: List<ULong>): List<Pair<Int, Int>> {
        // flat images share a hash, whatever they show
        val linkable = hashes.indices.filter { !isFlatHash(hashes[it]) }
        val values = LongArray(linkable.size) { hashes[linkable[it]].toLong() }
        return pairs(values.size) { a, b -> (values[a] xor values[b]).countOneBits() <= MAX_DISTANCE }
            .map { (a, b) -> linkable[a] to linkable[b] }
    }

    private suspend fun embeddingLinks(vectors: List<FloatArray?>): List<Pair<Int, Int>> {
        val present = vectors.indices.filter { vectors[it] != null }
        val values = present.map { vectors[it]!! }
        return pairs(values.size) { a, b -> dot(values[a], values[b]) >= NEAR_DUPLICATE_MIN_COSINE }
            .map { (a, b) -> present[a] to present[b] }
    }

    /**
     * The pairs (i, j), i < j < [count], that are [linked], in the order a loop over i then j finds them.
     * Blocks of rows run on every core: 50 million pairs took seconds on one.
     */
    private suspend inline fun pairs(count: Int, crossinline linked: (Int, Int) -> Boolean): List<Pair<Int, Int>> = coroutineScope {
        (0 until count step ROWS_PER_TASK).map { start ->
            async(Dispatchers.Default) {
                val links = mutableListOf<Pair<Int, Int>>()
                for (i in start until minOf(start + ROWS_PER_TASK, count)) {
                    ensureActive() // the pair loops run for seconds on a large library
                    for (j in (i + 1) until count) {
                        if (linked(i, j)) links += i to j
                    }
                }
                links
            }
        }.awaitAll().flatten()
    }

    private fun components(count: Int, links: List<Pair<Int, Int>>): List<List<Int>> {
        val parent = IntArray(count) { it }
        fun root(i: Int): Int {
            var curr = i
            while (parent[curr] != curr) {
                parent[curr] = parent[parent[curr]]
                curr = parent[curr]
            }
            return curr
        }

        for ((a, b) in links) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) {
                parent[ra] = rb
            }
        }

        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until count) {
            groups.getOrPut(root(i)) { mutableListOf() } += i
        }
        return groups.values.filter { it.size > 1 }
    }

    private class CandidatePreference(
        val index: Int,
        val pixels: Long,
        val bytesPerPixel: Double,
        val hasCameraExif: Boolean,
        val folder: Int,
        val path: String,
    )

    private suspend fun preference(index: Int, image: ImageRef, context: AnalysisContext): CandidatePreference {
        val meta = try {
            context.metadata(image)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            ImageMetadata(0, 0, null)
        }
        val pixels = (meta.width.toLong() * meta.height).coerceAtLeast(1L)
        val folder = when {
            isMessagingFolder(image.path) -> 2
            isCameraFolder(image.path) -> 0
            else -> 1
        }
        return CandidatePreference(
            index = index,
            pixels = meta.width.toLong() * meta.height,
            bytesPerPixel = image.size.toDouble() / pixels,
            hasCameraExif = meta.hasCameraExif,
            folder = folder,
            path = image.path,
        )
    }

    private companion object {
        /** Rows of the pair loops in one task: small, so the triangle's long first rows spread over the cores. */
        const val ROWS_PER_TASK = 32

        val PREFERENCE_COMPARATOR = compareBy<CandidatePreference>(
            { -it.pixels },
            { -it.bytesPerPixel },
            { !it.hasCameraExif },
            { it.folder },
            { it.path },
        )
    }
}
