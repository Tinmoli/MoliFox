# Third-party components and references

MoliFox's own application code and artwork are provided under the GNU GPLv3
license in LICENSE. Dependencies retain their own licenses; their JARs include
the upstream notices. Downloaded Java runtime notices remain in runtimes/*/legal.

| Component | License / source |
| --- | --- |
| Kotlin, coroutines | Apache-2.0 · https://github.com/JetBrains/kotlin |
| Compose Multiplatform, Material icons | Apache-2.0 · https://github.com/JetBrains/compose-multiplatform |
| Skiko / Skia | Apache-2.0 / BSD-3-Clause · https://github.com/JetBrains/skiko |
| Gson | Apache-2.0 · https://github.com/google/gson |
| OkHttp / Okio | Apache-2.0 · https://github.com/square/okhttp |
| Apache Commons Compress, IO, Lang, Codec | Apache-2.0 · https://commons.apache.org/ |
| XZ for Java | 0BSD · https://tukaani.org/xz/java.html |
| TomlJ 1.1.1 / ANTLR runtime | Apache-2.0 / BSD-3-Clause · https://github.com/tomlj/tomlj/tree/1.1.1 · https://github.com/antlr/antlr4 |
| JNA | Apache-2.0 OR LGPL-2.1-or-later · https://github.com/java-native-access/jna |
| Gradle wrapper | Apache-2.0 · https://github.com/gradle/gradle |
| Downloaded Temurin / OpenJDK runtime | GPL-2.0 with Classpath Exception; see runtimes/*/legal |
| MinGW-w64 native toolchain runtime | GCC runtime library exception; see https://www.gnu.org/licenses/gcc-exception-3.1.html |
| miniz 3.1.1 ZIP reader in native bootstrap | MIT; vendored license: launcher-bootstrap/native/miniz/LICENSE |

## Implementation references

These projects and protocol documents were consulted. MoliFox does not vendor,
modify, or link HMCL's GPL-licensed implementation. No HMCL binaries, logo,
assets, branding, or Java source files are included in the Kotlin application.

- HMCL launcher and install architecture: https://github.com/HMCL-dev/HMCL
- PCL UI workflow reference: https://github.com/Meloong-Git/PCL
- PCL Community Edition UI and storage reference: https://github.com/PCL-Community/PCL-CE
- Mojang version manifest: https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
- Fabric Meta: https://meta.fabricmc.net/
- Quilt Meta v3: https://github.com/QuiltMC/update-quilt-meta
- Forge installer format: https://github.com/MinecraftForge/Installer
- NeoForge Maven: https://maven.neoforged.net/
- Modrinth API and modpacks: https://docs.modrinth.com/
- Microsoft device authorization: https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code
- Temurin runtime distribution: https://api.adoptium.net/
- BMCLAPI mirror: https://bmclapidoc.bangbang93.com/

PCL and PCL-CE were consulted for account, launch, version-management, download
and navigation placement. Their source files, XAML, images, icons and branding
are not included. Their licenses remain separate from MoliFox's own code.

Minecraft is a trademark of Mojang. MoliFox is an independent project and is not
affiliated with Mojang or Microsoft. Minecraft game files are downloaded on
request and are not included in the launcher distribution.

Historical prototype source is kept only in docs/recovery/legacy-prototypes.zip
for recovery. Generated dependencies, caches and obsolete packaging trees were
removed. The backup is excluded from the Gradle build and distributions.
