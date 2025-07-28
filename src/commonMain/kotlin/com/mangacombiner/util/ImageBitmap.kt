package com.mangacombiner.util

import androidx.compose.ui.graphics.ImageBitmap

expect fun bytesToImageBitmap(bytes: ByteArray): ImageBitmap
