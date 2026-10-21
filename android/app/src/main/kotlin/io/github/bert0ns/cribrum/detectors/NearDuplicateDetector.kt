// NEAR_DUPLICATE: resized, recompressed and edited copies (docs/design.md §5). Mirrors
// lab/src/cribrum_lab/detectors/near_duplicate.py.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.folders.isCameraFolder
import io.github.bert0ns.cribrum.folders.isMessagingFolder
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
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
    override val version = 2
    override val category = Category.NEAR_DUPLICATE

    override suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>> {
        val members = mutableListOf<ImageRef>()
        val hashes = mutableListOf<ULong>()
        val vectors = mutableListOf<FloatArray?>()
        for (image in images) {
            val hash = try {
                context.perceptualHash(image)
            } catch (_: Exception) { // undecodable pixels: BitmapFactory returns null, files can vanish
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

    private fun hashLinks(hashes: List<ULong>): List<Pair<Int, Int>> {
        val links = mutableListOf<Pair<Int, Int>>()
        for (i in hashes.indices) {
            for (j in (i + 1) until hashes.size) {
                if ((hashes[i] xor hashes[j]).countOneBits() <= MAX_DISTANCE) {
                    links += i to j
                }
            }
        }
        return links
    }

    private fun embeddingLinks(vectors: List<FloatArray?>): List<Pair<Int, Int>> {
        val links = mutableListOf<Pair<Int, Int>>()
        for (i in vectors.indices) {
            val vi = vectors[i] ?: continue
            for (j in (i + 1) until vectors.size) {
                val vj = vectors[j] ?: continue
                if (dot(vi, vj) >= NEAR_DUPLICATE_MIN_COSINE) {
                    links += i to j
                }
            }
        }
        return links
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
        } catch (_: Exception) {
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
        val PREFERENCE_COMPARATOR = compareBy<CandidatePreference>(
            { -it.pixels },
            { -it.bytesPerPixel },
            { !it.hasCameraExif },
            { it.folder },
            { it.path },
        )
    }
}
