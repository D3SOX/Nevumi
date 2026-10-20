package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.pluralStringResource

/** Formats a quantity with its localized noun, e.g. "1 song" or "12 songs". */
@Composable
internal fun countLabel(count: Int, noun: PluralStringResource): String = pluralStringResource(noun, count, count)

internal fun countLabel(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count ${if (count == 1) singular else plural}"
