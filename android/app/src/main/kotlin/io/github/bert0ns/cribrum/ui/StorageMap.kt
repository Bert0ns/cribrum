package io.github.bert0ns.cribrum.ui

import kotlin.math.max
import kotlin.math.min

data class Rect(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
) {
    val right: Double get() = left + width
    val bottom: Double get() = top + height
    val area: Double get() = width * height
}

private data class TreemapItem(val index: Int, val area: Double)

/**
 * Lays out rectangles for [weights] inside [bounds] using the squarified treemap algorithm
 * (Bruls, Huizing and van Wijk, 2000). The returned list matches the indices of [weights].
 */
fun squarify(weights: List<Double>, bounds: Rect): List<Rect> {
    if (weights.isEmpty()) return emptyList()
    val result = Array(weights.size) { Rect(bounds.left, bounds.top, 0.0, 0.0) }
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

    fun layoutRow(row: List<TreemapItem>, rect: Rect): Rect {
        val rowArea = row.sumOf { it.area }
        val isHorizontal = rect.width >= rect.height
        val side = if (isHorizontal) rect.height else rect.width
        val thickness = if (side > 0.0) rowArea / side else 0.0

        var offset = 0.0
        for (item in row) {
            val length = if (thickness > 0.0) item.area / thickness else 0.0
            if (isHorizontal) {
                result[item.index] = Rect(
                    left = rect.left,
                    top = rect.top + offset,
                    width = thickness,
                    height = length,
                )
            } else {
                result[item.index] = Rect(
                    left = rect.left + offset,
                    top = rect.top,
                    width = length,
                    height = thickness,
                )
            }
            offset += length
        }

        return if (isHorizontal) {
            Rect(
                left = rect.left + thickness,
                top = rect.top,
                width = max(0.0, rect.width - thickness),
                height = rect.height,
            )
        } else {
            Rect(
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
