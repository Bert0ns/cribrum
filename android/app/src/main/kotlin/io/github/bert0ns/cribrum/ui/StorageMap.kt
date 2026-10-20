package io.github.bert0ns.cribrum.ui

import io.github.bert0ns.cribrum.model.ImageRef
import kotlin.math.max
import kotlin.math.min

data class MapRect(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
) {
    val right: Double get() = left + width
    val bottom: Double get() = top + height
    val area: Double get() = width * height

    fun contains(x: Double, y: Double): Boolean =
        x in left..right && y in top..bottom
}

data class Folder(
    val name: String,
    val path: String,
    val size: Long,
    val imageCount: Int,
    val children: List<Folder> = emptyList(),
) {
    val directSize: Long get() = size - children.sumOf { it.size }
    val directImageCount: Int get() = imageCount - children.sumOf { it.imageCount }
}

private class MutableFolder(
    val name: String,
    val path: String,
) {
    var directSize: Long = 0L
    var directImageCount: Int = 0
    val subfolders = mutableMapOf<String, MutableFolder>()

    fun toFolder(): Folder {
        val childFolders = subfolders.values.map { it.toFolder() }.sortedByDescending { it.size }
        val totalSize = directSize + childFolders.sumOf { it.size }
        val totalCount = directImageCount + childFolders.sumOf { it.imageCount }
        return Folder(
            name = name,
            path = path,
            size = totalSize,
            imageCount = totalCount,
            children = childFolders,
        )
    }
}

/**
 * Builds a [Folder] tree from a list of images. Common single-child parent
 * folders with no direct files are collapsed so the tree starts at the first branching point.
 */
fun buildFolderTree(images: List<ImageRef>): Folder {
    if (images.isEmpty()) return Folder(name = "", path = "", size = 0L, imageCount = 0)

    val root = MutableFolder(name = "", path = "")
    for (image in images) {
        val parts = image.path.split('/').filter { it.isNotEmpty() }
        if (parts.size <= 1) {
            root.directSize += image.size
            root.directImageCount += 1
        } else {
            val folderSegments = parts.dropLast(1)
            var current = root
            var currentPath = ""
            val isAbsolute = image.path.startsWith("/")
            for (segment in folderSegments) {
                currentPath = if (currentPath.isEmpty() && !isAbsolute) segment else "$currentPath/$segment"
                current = current.subfolders.getOrPut(segment) { MutableFolder(segment, currentPath) }
            }
            current.directSize += image.size
            current.directImageCount += 1
        }
    }

    var result = root.toFolder()
    while (result.children.size == 1 && result.directImageCount == 0) {
        result = result.children.single()
    }
    return result
}

private data class TreemapItem(val index: Int, val area: Double)

/**
 * Lays out rectangles for [weights] inside [bounds] using the squarified treemap algorithm
 * (Bruls, Huizing and van Wijk, 2000). The returned list matches the indices of [weights].
 */
fun squarify(weights: List<Double>, bounds: MapRect): List<MapRect> {
    if (weights.isEmpty()) return emptyList()
    val result = Array(weights.size) { MapRect(bounds.left, bounds.top, 0.0, 0.0) }
    if (bounds.width <= 0.0 || bounds.height <= 0.0) return result.toList()

    val positiveItems = weights.mapIndexedNotNull { index, weight ->
        if (weight > 0.0) TreemapItem(index, weight) else null
    }
    if (positiveItems.isEmpty()) return result.toList()

    val totalWeight = positiveItems.sumOf { it.area }
    val totalArea = bounds.width * bounds.height
    val scale = totalArea / totalWeight
    val items = positiveItems.map { TreemapItem(it.index, it.area * scale) }
        .sortedByDescending { it.area }

    var currentBounds = bounds
    var currentRow = mutableListOf<TreemapItem>()

    fun worst(row: List<TreemapItem>, side: Double): Double {
        if (row.isEmpty() || side <= 0.0) return Double.MAX_VALUE
        val rowArea = row.sumOf { it.area }
        val s2 = side * side
        val rowArea2 = rowArea * rowArea
        var maxRatio = 0.0
        for (item in row) {
            val a = item.area
            val r = max(s2 * a / rowArea2, rowArea2 / (s2 * a))
            if (r > maxRatio) maxRatio = r
        }
        return maxRatio
    }

    fun layoutRow(row: List<TreemapItem>, rect: MapRect): MapRect {
        val rowArea = row.sumOf { it.area }
        val isHorizontal = rect.width >= rect.height
        val side = if (isHorizontal) rect.height else rect.width
        val thickness = if (side > 0.0) rowArea / side else 0.0

        var offset = 0.0
        for (item in row) {
            val length = if (thickness > 0.0) item.area / thickness else 0.0
            if (isHorizontal) {
                result[item.index] = MapRect(
                    left = rect.left,
                    top = rect.top + offset,
                    width = thickness,
                    height = length,
                )
            } else {
                result[item.index] = MapRect(
                    left = rect.left + offset,
                    top = rect.top,
                    width = length,
                    height = thickness,
                )
            }
            offset += length
        }

        return if (isHorizontal) {
            MapRect(
                left = rect.left + thickness,
                top = rect.top,
                width = max(0.0, rect.width - thickness),
                height = rect.height,
            )
        } else {
            MapRect(
                left = rect.left,
                top = rect.top + thickness,
                width = rect.width,
                height = max(0.0, rect.height - thickness),
            )
        }
    }

    for (item in items) {
        val side = min(currentBounds.width, currentBounds.height)
        if (currentRow.isEmpty()) {
            currentRow.add(item)
        } else {
            val withItem = currentRow + item
            if (worst(withItem, side) <= worst(currentRow, side)) {
                currentRow.add(item)
            } else {
                currentBounds = layoutRow(currentRow, currentBounds)
                currentRow = mutableListOf(item)
            }
        }
    }
    if (currentRow.isNotEmpty()) {
        layoutRow(currentRow, currentBounds)
    }

    return result.toList()
}

data class TreemapBlock(
    val rect: MapRect,
    val folder: Folder,
    val isDirectFiles: Boolean,
    val depth: Int,
    val hueIndex: Int,
)

/** Returns a distinct hue in degrees (0..360) for a top-level block index. */
fun folderHue(hueIndex: Int): Float = ((hueIndex * 137.5f) % 360f + 360f) % 360f

/**
 * Recursively lays out a treemap for [folder] inside [bounds] with a [gap] between nesting levels.
 * Rectangles whose width or height is less than [minSize] are omitted.
 */
fun layoutTreemap(
    folder: Folder,
    bounds: MapRect,
    gap: Double,
    minSize: Double,
): List<TreemapBlock> {
    if (folder.size <= 0L || bounds.width < minSize || bounds.height < minSize) return emptyList()
    val blocks = mutableListOf<TreemapBlock>()

    fun recurse(
        current: Folder,
        rect: MapRect,
        depth: Int,
        hueIndex: Int,
    ) {
        if (rect.width < minSize || rect.height < minSize) return

        if (depth >= 0) {
            blocks.add(TreemapBlock(rect, current, isDirectFiles = false, depth = depth, hueIndex = hueIndex))
            if (current.children.isEmpty()) return
        }

        val inner = if (depth < 0) {
            rect
        } else {
            MapRect(
                left = rect.left + gap,
                top = rect.top + gap,
                width = max(0.0, rect.width - 2 * gap),
                height = max(0.0, rect.height - 2 * gap),
            )
        }
        if (inner.width < minSize || inner.height < minSize) return

        val children = current.children.filter { it.size > 0L }
        val hasDirect = current.directSize > 0L
        if (children.isEmpty() && !hasDirect) return

        val items = mutableListOf<Pair<Folder?, Long>>()
        for (child in children) items.add(child to child.size)
        if (hasDirect) items.add(null to current.directSize)

        val weights = items.map { it.second.toDouble() }
        val rects = squarify(weights, inner)

        for (i in items.indices) {
            val (childFolder, _) = items[i]
            val r = rects[i]
            if (r.width < minSize || r.height < minSize) continue
            val hue = if (depth < 0) i else hueIndex
            if (childFolder != null) {
                recurse(childFolder, r, depth + 1, hue)
            } else {
                val isDirect = depth >= 0 || children.isNotEmpty()
                blocks.add(TreemapBlock(r, current, isDirectFiles = isDirect, depth = depth + 1, hueIndex = hue))
            }
        }
    }

    recurse(folder, bounds, depth = -1, hueIndex = 0)
    return blocks
}

/** The top-level block under a tap, so tapping anywhere inside a folder selects that folder. */
fun topLevelBlockAt(blocks: List<TreemapBlock>, x: Double, y: Double): TreemapBlock? =
    blocks.firstOrNull { it.depth == 0 && it.rect.contains(x, y) }
