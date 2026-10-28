package com.truesim.build.slang

import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipInputStream

/**
 * The Slang compiler, downloaded once per version and platform from Slang's GitHub releases into the Gradle user home,
 * checked against a pinned SHA-256 and unpacked. Builds and CI then use exactly the pinned compiler.
 */
object SlangToolchain {

    /** The Slang release the plugin pins by default. */
    const val DEFAULT_VERSION = "2026.20"

    /** SHA-256 of each platform's release zip, per pinned version. */
    val CHECKSUMS: Map<String, Map<String, String>> = mapOf(
        "2026.20" to mapOf(
            "windows-x86_64" to "e6dd32bbddf1720ebb4e679d55e9635ce792865a5bd84f3d2cd9af28647c0174",
            "windows-aarch64" to "4f18a7e76a59969bc9bf6ea596bd9e496a3c689af173990ae028b18f03e1ac1a",
            "linux-x86_64" to "73681e314bd48c1396daf963a66647724519b152886961d8ca3b1b7e477cf640",
            "linux-aarch64" to "1901efe981900908faba8109bcffaf4df48068bb0e5e42ffadb908af525e7ed8",
            "macos-x86_64" to "a71d4a499d08b6178325a697a5a5c9fddc9eee5914daaf195451b283189e96ce",
            "macos-aarch64" to "ede1865bf086442c8786c5b7c0470ce5f4fd4f032eefe881356726ff9c445f40",
        ),
    )

    /** This machine's platform in Slang's release names, e.g. `windows-x86_64`. */
    fun platform(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val system = when {
            os.contains("win") -> "windows"
            os.contains("mac") -> "macos"
            os.contains("linux") -> "linux"
            else -> throw IllegalStateException("No Slang release for $os")
        }
        val cpu = if (arch == "aarch64" || arch == "arm64") "aarch64" else "x86_64"
        return "$system-$cpu"
    }

    /**
     * The `slangc` executable of [version], installed under [cacheDir] if it is not yet; [checksum] is the release
     * zip's SHA-256 for this platform. Safe when several builds install at once.
     */
    @Synchronized
    fun install(cacheDir: File, version: String, checksum: String): File {
        val platform = platform()
        val home = File(cacheDir, "slang/$version/$platform")
        val done = File(home, ".installed")
        if (!done.exists()) {
            home.mkdirs()
            RandomAccessFile(File(home.parentFile, "$platform.lock"), "rw").channel.use { channel ->
                channel.lock().use {
                    if (!done.exists()) {
                        download(version, platform, checksum, home)
                        done.writeText(checksum)
                    }
                }
            }
        }
        val name = if (platform.startsWith("windows")) "slangc.exe" else "slangc"
        return home.walkTopDown().firstOrNull { it.isFile && it.name == name && it.parentFile.name == "bin" }
            ?: throw IllegalStateException("No $name in the Slang $version release for $platform")
    }

    private fun download(version: String, platform: String, checksum: String, home: File) {
        val url = "https://github.com/shader-slang/slang/releases/download/v$version/slang-$version-$platform.zip"
        val zip = File.createTempFile("slang-", ".zip")
        try {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            val response = client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofFile(zip.toPath()))
            check(response.statusCode() == 200) { "Could not download $url: HTTP ${response.statusCode()}" }
            val digest = MessageDigest.getInstance("SHA-256")
            zip.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = HexFormat.of().formatHex(digest.digest())
            check(actual.equals(checksum, ignoreCase = true)) {
                "Slang $version for $platform has SHA-256 $actual, expected $checksum: refusing to use it"
            }
            unzip(zip, home)
        } finally {
            zip.delete()
        }
    }

    private fun unzip(zip: File, home: File) {
        val root = home.canonicalFile.toPath()
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val target = root.resolve(entry.name).normalize()
                check(target.startsWith(root)) { "Zip entry outside the install directory: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    if (target.parent.fileName.toString() == "bin") {
                        target.toFile().setExecutable(true)
                    }
                }
            }
        }
    }
}
