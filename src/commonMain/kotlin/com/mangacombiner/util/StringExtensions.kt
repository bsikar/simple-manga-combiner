package com.mangacombiner.util

import java.util.Locale

public fun String.titlecase(): String = this.split(' ').joinToString(" ") { word ->
    word.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
    }
}

public fun String.toSlug(): String = this.lowercase()
    .replace(Regex("https?://(www\\.)?"), "") // Remove http/s and www
    .replace(Regex("[^a-z0-9]"), "-") // Replace non-alphanumeric with hyphen
    .replace(Regex("-+"), "-") // Replace multiple hyphens with one
    .trim('-')
