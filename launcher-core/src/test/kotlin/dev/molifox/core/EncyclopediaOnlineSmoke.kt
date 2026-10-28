// 只读验证公开百科的真实项目对应关系，不携带用户配置或账户信息。

package dev.molifox.core

import java.nio.file.Path
import kotlinx.coroutines.runBlocking

object EncyclopediaOnlineSmoke {
    @JvmStatic
    fun main(arguments: Array<String>) = runBlocking {
        NetworkClient().use { network ->
            val hit = ContentHit("fabric-api", "fabric-api", "Fabric API", "", "", 0, "mod", null)
            val link = EncyclopediaLinks(network).resolve(hit)
            val passed = link == "https://www.mcmod.cn/class/3124.html"
            writeJson(
                Path.of(arguments.single()),
                mapOf(
                    "timestamp" to java.time.Instant.now().toString(),
                    "passed" to passed,
                    "project" to "https://modrinth.com/mod/fabric-api",
                    "encyclopedia" to link,
                    "scope" to "read-only public project identity lookup",
                ),
            )
            check(passed) { "公开百科对应关系未能确认，界面会隐藏该入口" }
            println("ENCYCLOPEDIA_LOOKUP_OK $link")
        }
    }
}
