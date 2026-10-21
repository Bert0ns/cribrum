package io.github.bert0ns.cribrum.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.pipeline.DETECTOR_CATEGORIES
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import io.github.bert0ns.cribrum.review.Thresholds
import kotlin.math.roundToInt

/** The score a signal needs to make an image a candidate, per category (docs/design.md §6.1). */
@Composable
fun ThresholdsScreen(
    thresholds: Map<Category, Float>,
    counts: Map<Category, Int>,
    defaultCounts: Map<Category, Int>,
    onChange: (Category, Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.thresholds_intro))
                Text(
                    stringResource(R.string.thresholds_raising),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        items(Category.entries.filter { it in DETECTOR_CATEGORIES }, key = { it }) { category ->
            val value = thresholds[category] ?: THRESHOLDS.getValue(category)
            val count = counts[category] ?: 0
            val change = count - (defaultCounts[category] ?: count)
            var shown by remember(value) { mutableFloatStateOf(value) }
            val name = categoryName(category)
            ListItem(
                headlineContent = { Text("$name: ${"%.2f".format(shown)}") },
                supportingContent = {
                    Column {
                        Slider(
                            value = shown,
                            onValueChange = {
                                val step = (it * 20).roundToInt() / 20f
                                if (step != shown) haptics(Haptic.TICK)
                                shown = step
                            },
                            onValueChangeFinished = { onChange(category, shown) },
                            valueRange = Thresholds.RANGE,
                            steps = 18,
                            modifier = Modifier.semantics { contentDescription = name },
                        )
                        Text(pluralStringResource(R.plurals.candidates, count, count))
                        when {
                            change > 0 -> Text(pluralStringResource(R.plurals.thresholds_more, change, change))
                            change < 0 -> Text(pluralStringResource(R.plurals.thresholds_fewer, -change, -change))
                        }
                    }
                },
            )
        }
        item {
            OutlinedButton({
                haptics(Haptic.KEEP)
                onReset()
            }, Modifier.fillMaxWidth().padding(16.dp)) { Text(stringResource(R.string.thresholds_reset)) }
        }
    }
}
