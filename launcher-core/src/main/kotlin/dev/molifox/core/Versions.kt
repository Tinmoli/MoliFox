package dev.molifox.core

/** Meta services do not all return loader releases in chronological order. */
fun compareLoaderVersions(left: String, right: String): Int {
    fun numericBase(value: String) =
        value.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
    val leftBase = numericBase(left)
    val rightBase = numericBase(right)
    for (index in 0 until maxOf(leftBase.size, rightBase.size)) {
        val compared = leftBase.getOrElse(index) { 0 }.compareTo(rightBase.getOrElse(index) { 0 })
        if (compared != 0) return compared
    }
    val leftQualifier = left.substringBefore('+').substringAfter('-', "")
    val rightQualifier = right.substringBefore('+').substringAfter('-', "")
    if (leftQualifier.isBlank() != rightQualifier.isBlank())
        return if (leftQualifier.isBlank()) 1 else -1
    val leftTokens = leftQualifier.split('.', '-')
    val rightTokens = rightQualifier.split('.', '-')
    for (index in 0 until maxOf(leftTokens.size, rightTokens.size)) {
        val a = leftTokens.getOrElse(index) { "" }
        val b = rightTokens.getOrElse(index) { "" }
        val compared =
            if (a.toIntOrNull() != null && b.toIntOrNull() != null) a.toInt().compareTo(b.toInt())
            else a.compareTo(b)
        if (compared != 0) return compared
    }
    return 0
}
