// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证 Maven 区间、SemVer 谓词、版本词边界与排序。

package dev.molifox.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JarInJarVersioningTest {
    private fun forge(
        constraint: String,
        version: String,
    ) = JarInJarVersioning.satisfies(constraint, JarInJarLoader.Forge, version)

    private fun fabric(
        constraint: String,
        version: String,
    ) = JarInJarVersioning.satisfies(constraint, JarInJarLoader.Fabric, version)

    @Test
    fun `maven intervals respect open and closed bounds`() {
        assertTrue(forge("[1.20,1.21)", "1.20"))
        assertTrue(forge("[1.20,1.21)", "1.20.6"))
        assertFalse(forge("[1.20,1.21)", "1.21"))
        assertTrue(forge("[1.20]", "1.20"))
        assertFalse(forge("[1.20]", "1.20.1"))
        assertFalse(forge("(,2.0)", "3.0"))
        assertTrue(forge("(,2.0)", "1.9"))
        // 裸版本在 Forge 里是软下限。
        assertTrue(forge("1.20", "1.20.4"))
        assertFalse(forge("1.20", "1.19.4"))
    }

    @Test
    fun `semver predicates support comparators wildcards tilde caret and unions`() {
        assertTrue(fabric(">=1.20 <1.21", "1.20.5"))
        assertFalse(fabric(">=1.20 <1.21", "1.21"))
        assertTrue(fabric("1.20.x", "1.20.9"))
        assertFalse(fabric("1.20.x", "1.21.1"))
        assertTrue(fabric("~1.20", "1.20.9"))
        assertFalse(fabric("~1.20", "1.21"))
        assertTrue(fabric("^1.2", "1.9"))
        assertFalse(fabric("^1.2", "2.0"))
        assertTrue(fabric(">=1.20 || <1.10", "1.9"))
        assertTrue(fabric(">=26.1-", "26.1-pre1"))
        // Fabric 裸版本是精确匹配。
        assertTrue(fabric("1.20.1", "1.20.1"))
        assertFalse(fabric("1.20.1", "1.20.10"))
    }

    @Test
    fun `empty constraint accepts any version and blank version is rejected`() {
        assertTrue(forge("", "1.0"))
        assertTrue(forge("*", "1.0"))
        assertFalse(forge("[1.0,]", ""))
    }

    @Test
    fun `has comparable bound distinguishes malformed constraints`() {
        assertFalse(JarInJarVersioning.hasComparableBound(""))
        assertFalse(JarInJarVersioning.hasComparableBound("latest"))
        assertTrue(JarInJarVersioning.hasComparableBound("(,2.0)"))
        assertTrue(JarInJarVersioning.hasComparableBound(">=1.0"))
    }

    @Test
    fun `version token matching requires a complete version word`() {
        assertTrue(JarInJarVersioning.containsVersionToken("mod-1.21.jar", "1.21"))
        assertFalse(JarInJarVersioning.containsVersionToken("mod-1.21.2.jar", "1.21"))
        assertFalse(JarInJarVersioning.containsVersionToken("mod-9.1.20.jar", "1.20"))
        assertTrue(JarInJarVersioning.containsVersionToken("version 1.20.1", "1.20.1"))
        assertFalse(JarInJarVersioning.containsVersionToken("", "1.20"))
    }

    @Test
    fun `versions sort numerically and prereleases sort below releases`() {
        assertTrue(JarInJarVersioning.compare("1.10", "1.9") > 0)
        assertTrue(JarInJarVersioning.compare("1.0.0-beta", "1.0.0") < 0)
        assertEquals(0, JarInJarVersioning.compare("v1.20.1", "1.20.1"))
    }
}
