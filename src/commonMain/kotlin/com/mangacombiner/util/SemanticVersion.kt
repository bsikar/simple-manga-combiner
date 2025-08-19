package com.mangacombiner.util

class SemanticVersion(versionString: String) : Comparable<SemanticVersion> {
    private val parts: List<Int>

    init {
        parts = versionString.split(".").map { it.toIntOrNull() ?: 0 }
    }

    override fun compareTo(other: SemanticVersion): Int {
        val maxParts = maxOf(this.parts.size, other.parts.size)
        for (i in 0 until maxParts) {
            val thisPart = this.parts.getOrElse(i) { 0 }
            val otherPart = other.parts.getOrElse(i) { 0 }
            if (thisPart != otherPart) {
                return thisPart.compareTo(otherPart)
            }
        }
        return 0
    }
}
