// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏版本、平台规则和版本清单转换。

package dev.molifox.core

/** LiteLoader 与 Cleanroom 只用于识别和启动已有实例，不提供新建与升级入口。 */
val LoaderType.launchOnly: Boolean
    get() = this == LoaderType.LiteLoader || this == LoaderType.Cleanroom

/** Meta services do not all return loader releases in chronological order. */
fun compareLoaderVersions(
    left: String,
    right: String,
): Int {
    fun numericBase(value: String) =
        value
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
    val leftBase = numericBase(left)
    val rightBase = numericBase(right)
    val baseComparison =
        (0 until maxOf(leftBase.size, rightBase.size)).firstNotNullOfOrNull { index ->
            leftBase
                .getOrElse(index) { 0 }
                .compareTo(rightBase.getOrElse(index) { 0 })
                .takeIf { it != 0 }
        }
    val leftQualifier = left.substringBefore('+').substringAfter('-', "")
    val rightQualifier = right.substringBefore('+').substringAfter('-', "")
    val qualifierComparison =
        when {
            leftQualifier.isBlank() != rightQualifier.isBlank() ->
                if (leftQualifier.isBlank()) 1 else -1
            leftQualifier.isBlank() -> null
            else -> {
                val leftTokens = leftQualifier.split('.', '-')
                val rightTokens = rightQualifier.split('.', '-')
                (0 until maxOf(leftTokens.size, rightTokens.size)).firstNotNullOfOrNull { index ->
                    val a = leftTokens.getOrElse(index) { "" }
                    val b = rightTokens.getOrElse(index) { "" }
                    val compared =
                        if (a.toIntOrNull() != null && b.toIntOrNull() != null) {
                            a.toInt().compareTo(b.toInt())
                        } else {
                            a.compareTo(b)
                        }
                    compared.takeIf { it != 0 }
                }
            }
        }
    return baseComparison ?: qualifierComparison ?: 0
}
