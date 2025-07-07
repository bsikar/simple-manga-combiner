package com.mangacombiner.util

import java.util.Locale

public fun String.titlecase(): String = this.split(' ').joinToString(" ") { word ->
    word.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
    }
}
