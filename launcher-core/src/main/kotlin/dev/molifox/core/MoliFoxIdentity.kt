// 负责启动器版本、品牌和认证应用标识。

package dev.molifox.core

/** Public OAuth application identity; this is not a client secret. */
object MoliFoxIdentity {
    const val microsoftClientId = "f7a97d7c-8dc7-42eb-81bb-5bf19c29d213"
    const val repository = "https://github.com/Tinmoli/MoliFox"
    const val releases = "$repository/releases"
    const val issues = "$repository/issues"
    const val license = "GPLv3 · 保留署名与修改版区分"
}
