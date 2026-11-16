package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.noxer.auth.AccountSessionManager
import com.example.noxer.auth.MicrosoftAuthManager
import com.example.noxer.auth.OfflineProfileIcon
import com.example.noxer.auth.OfflineProfileManager
import com.example.noxer.console.ConsoleLogger
import com.example.noxer.core.NoxerPaths
import com.example.noxer.data.db.InstallationEntity
import com.example.noxer.data.db.InstallationStatus
import com.example.noxer.data.db.NoxerDatabase
import com.example.noxer.download.DownloadEngine
import com.example.noxer.download.MojangRuleEvaluator
import com.example.noxer.graphics.RendererManager
import com.example.noxer.launch.CrashHandler
import com.example.noxer.launch.GameLauncher
import com.example.noxer.launch.JvmLauncherBackend
import com.example.noxer.launch.LaunchErrorCategory
import com.example.noxer.launch.LaunchExecutionPlan
import com.example.noxer.mods.ModLoaderManager
import com.example.noxer.runtime.AndroidAbiInfo
import com.example.noxer.runtime.AndroidRuntimeSourceResolver
import com.example.noxer.runtime.AndroidRuntimeVerifier
import com.example.noxer.runtime.ArchiveExtractor
import com.example.noxer.runtime.JavaRuntimeManager
import com.example.noxer.runtime.RuntimeArchiveFormat
import com.example.noxer.runtime.RuntimeExecutionProbe
import com.example.noxer.runtime.RuntimeInstallProgress
import com.example.noxer.runtime.RuntimeRepositoryEndpoints
import com.example.noxer.runtime.RuntimeSourceResolver
import com.example.noxer.settings.SettingsManager
import com.example.noxer.version.AssetIndexInfo
import com.example.noxer.version.DownloadArtifact
import com.example.noxer.version.JavaVersionInfo
import com.example.noxer.version.LibraryDownloads
import com.example.noxer.version.LibraryEntry
import com.example.noxer.version.OsRule
import com.example.noxer.version.RuleEntry
import com.example.noxer.version.VersionCenterRepository
import com.example.noxer.version.VersionDetails
import com.example.noxer.version.VersionDownloads
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JavaRuntimeEngineTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Builds a real ELF64 little-endian binary containing optional PT_INTERP and SHT_DYNAMIC+DT_NEEDED
     * sections so our ELF validator and glibc detector operate on genuine ELF structures.
     */
    private fun buildTestElf64(
        eMachine: Int,
        interpreter: String? = null,
        neededLibs: List<String> = emptyList(),
    ): ByteArray {
        val dynStr = ByteArrayOutputStream()
        dynStr.write(0)
        val nameOffsets = neededLibs.map { lib ->
            val off = dynStr.size()
            dynStr.write(lib.toByteArray(Charsets.UTF_8))
            dynStr.write(0)
            off
        }
        val dynStrBytes = dynStr.toByteArray()

        // Dynamic entries: (neededLibs count + DT_NULL) * 16 bytes
        val dynTable = ByteBuffer.allocate((nameOffsets.size + 1) * 16).order(ByteOrder.LITTLE_ENDIAN)
        for (off in nameOffsets) {
            dynTable.putLong(1L) // DT_NEEDED
            dynTable.putLong(off.toLong())
        }
        dynTable.putLong(0L) // DT_NULL
        dynTable.putLong(0L)
        val dynBytes = dynTable.array()

        val interpBytes = interpreter?.let { (it + "\u0000").toByteArray(Charsets.UTF_8) } ?: ByteArray(0)

        val ehdrSize = 64
        val phdrSize = if (interpBytes.isNotEmpty()) 56 else 0
        val interpOffset = ehdrSize + phdrSize
        val dynOffset = interpOffset + interpBytes.size
        val strOffset = dynOffset + dynBytes.size
        val shoff = strOffset + dynStrBytes.size
        val shnum = 3 // 0: NULL, 1: .dynamic, 2: .dynstr

        val totalSize = shoff + shnum * 64
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // ELF header (64 bytes)
        buf.put(0, 0x7F.toByte())
        buf.put(1, 'E'.code.toByte())
        buf.put(2, 'L'.code.toByte())
        buf.put(3, 'F'.code.toByte())
        buf.put(4, 2) // ELFCLASS64
        buf.put(5, 1) // ELFDATA2LSB
        buf.put(6, 1) // EV_CURRENT
        buf.putShort(16, 3) // ET_DYN
        buf.putShort(18, eMachine.toShort())
        buf.putInt(20, 1)
        buf.putLong(32, if (phdrSize > 0) ehdrSize.toLong() else 0L) // e_phoff
        buf.putLong(40, shoff.toLong()) // e_shoff
        buf.putShort(52, 64) // e_ehsize
        buf.putShort(54, if (phdrSize > 0) 56 else 0) // e_phentsize
        buf.putShort(56, if (phdrSize > 0) 1 else 0) // e_phnum
        buf.putShort(58, 64) // e_shentsize
        buf.putShort(60, shnum.toShort()) // e_shnum
        buf.putShort(62, 0)

        if (phdrSize > 0) {
            val p = ehdrSize
            buf.putInt(p, 3) // PT_INTERP
            buf.putLong(p + 8, interpOffset.toLong()) // p_offset
            buf.putLong(p + 32, interpBytes.size.toLong()) // p_filesz
        }

        if (interpBytes.isNotEmpty()) {
            buf.position(interpOffset)
            buf.put(interpBytes)
        }
        buf.position(dynOffset)
        buf.put(dynBytes)
        buf.position(strOffset)
        buf.put(dynStrBytes)

        // Section 0: NULL (already zeros)
        // Section 1: .dynamic (sh_type = 6, sh_link = 2)
        val sh1 = shoff + 64
        buf.putInt(sh1 + 4, 6) // SHT_DYNAMIC
        buf.putLong(sh1 + 24, dynOffset.toLong())
        buf.putLong(sh1 + 32, dynBytes.size.toLong())
        buf.putInt(sh1 + 40, 2) // sh_link -> .dynstr

        // Section 2: .dynstr (sh_type = 3)
        val sh2 = shoff + 128
        buf.putInt(sh2 + 4, 3) // SHT_STRTAB
        buf.putLong(sh2 + 24, strOffset.toLong())
        buf.putLong(sh2 + 32, dynStrBytes.size.toLong())

        return buf.array()
    }

    private fun createRawTarGz(entries: List<Pair<String, ByteArray>>): ByteArray {
        val tarStream = ByteArrayOutputStream()
        for ((fullName, payload) in entries) {
            val header = ByteArray(512)
            val nameBytes = fullName.toByteArray(Charsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
            val isExec = fullName.contains("/bin/") || fullName.endsWith(".so") || fullName.contains(".so.")
            val modeStr = if (isExec) "0000755\u0000" else "0000644\u0000"
            System.arraycopy(modeStr.toByteArray(Charsets.US_ASCII), 0, header, 100, 8)
            System.arraycopy("0000000\u0000".toByteArray(Charsets.US_ASCII), 0, header, 108, 8)
            System.arraycopy("0000000\u0000".toByteArray(Charsets.US_ASCII), 0, header, 116, 8)
            val sizeStr = "%011o\u0000".format(payload.size.toLong())
            System.arraycopy(sizeStr.toByteArray(Charsets.US_ASCII), 0, header, 124, 12)
            System.arraycopy("00000000000\u0000".toByteArray(Charsets.US_ASCII), 0, header, 136, 12)
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            header[156] = '0'.code.toByte()
            System.arraycopy("ustar\u0000".toByteArray(Charsets.US_ASCII), 0, header, 257, 6)
            System.arraycopy("00".toByteArray(Charsets.US_ASCII), 0, header, 263, 2)
            val checksum = header.sumOf { it.toInt() and 0xFF }
            val cksumStr = "%06o\u0000 ".format(checksum)
            System.arraycopy(cksumStr.toByteArray(Charsets.US_ASCII), 0, header, 148, 8)
            tarStream.write(header)
            if (payload.isNotEmpty()) {
                tarStream.write(payload)
                val rem = payload.size % 512
                if (rem != 0) tarStream.write(ByteArray(512 - rem))
            }
        }
        tarStream.write(ByteArray(1024)) // EOF blocks
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(tarStream.toByteArray()) }
        return out.toByteArray()
    }

    private fun createRuntimeTarGz(entries: Map<String, ByteArray>, rootDirName: String = "jre21-arm64"): ByteArray =
        createRawTarGz(entries.map { (relPath, payload) -> "$rootDirName/$relPath" to payload })

    private fun createArDeb(dataTarGz: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("!<arch>\n".toByteArray(Charsets.US_ASCII))
        val hdr = "%-16s%-12s%-6s%-6s%-8s%-10d`\n".format(
            "data.tar.gz", "0", "0", "0", "100644", dataTarGz.size
        ).toByteArray(Charsets.US_ASCII)
        out.write(hdr)
        out.write(dataTarGz)
        if (dataTarGz.size % 2 != 0) out.write('\n'.code)
        return out.toByteArray()
    }

    @Test
    fun androidRuntimeVerifierRejectsDesktopGlibcAndWrongAbi() = runTest {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "noxer_verify_${System.nanoTime()}").apply { mkdirs() }
        try {
            val probe = RuntimeExecutionProbe { _, _ -> "OK: Executed JVM 21.0.5 (OpenJDK 64-Bit Server VM, aarch64)" }
            val verifier = AndroidRuntimeVerifier(probe)

            // 1. Desktop Linux glibc AArch64 runtime MUST be rejected cleanly
            val glibcHome = File(tmpDir, "glibc_jre").apply { mkdirs() }
            File(glibcHome, "lib/server").mkdirs()
            File(glibcHome, "bin").mkdirs()
            File(glibcHome, "lib/modules").writeBytes(byteArrayOf(1, 2, 3))
            File(glibcHome, "lib/server/libjvm.so").writeBytes(
                buildTestElf64(
                    eMachine = 0xB7, // AArch64
                    neededLibs = listOf("libc.so.6", "libpthread.so.0", "libdl.so.2"),
                )
            )
            File(glibcHome, "bin/java").writeBytes(
                buildTestElf64(
                    eMachine = 0xB7,
                    interpreter = "/lib/ld-linux-aarch64.so.1",
                    neededLibs = listOf("libc.so.6"),
                )
            )
            val glibcResult = verifier.verify(glibcHome, expectedAbi = "arm64-v8a", expectedMajorVersion = 21)
            assertFalse(glibcResult.isVerified)
            assertFalse(glibcResult.isAndroidBionic)
            assertTrue(glibcResult.diagnosticMessage.contains("GLIBC_INCOMPATIBLE"))

            // 2. Wrong architecture (x86-64 on arm64-v8a) MUST be rejected
            val x64Home = File(tmpDir, "x64_jre").apply { mkdirs() }
            File(x64Home, "lib/server").mkdirs()
            File(x64Home, "lib/modules").writeBytes(byteArrayOf(1, 2, 3))
            File(x64Home, "lib/server/libjvm.so").writeBytes(
                buildTestElf64(eMachine = 0x3E, neededLibs = listOf("libc.so", "libdl.so"))
            )
            val archResult = verifier.verify(x64Home, expectedAbi = "arm64-v8a", expectedMajorVersion = 21)
            assertFalse(archResult.isVerified)
            assertTrue(archResult.diagnosticMessage.contains("ARCH_MISMATCH"))

            // 3. Genuine Android Bionic AArch64 runtime passes
            val bionicHome = File(tmpDir, "bionic_jre").apply { mkdirs() }
            File(bionicHome, "lib/server").mkdirs()
            File(bionicHome, "bin").mkdirs()
            File(bionicHome, "release").writeText("JAVA_VERSION=\"21.0.5\"\n")
            File(bionicHome, "lib/modules").writeBytes(byteArrayOf(1, 2, 3))
            File(bionicHome, "lib/server/libjvm.so").writeBytes(
                buildTestElf64(eMachine = 0xB7, neededLibs = listOf("libc.so", "libdl.so", "libm.so"))
            )
            File(bionicHome, "bin/java").writeBytes(
                buildTestElf64(
                    eMachine = 0xB7,
                    interpreter = "/system/bin/linker64",
                    neededLibs = listOf("libc.so", "libjli.so"),
                )
            )
            val okResult = verifier.verify(bionicHome, expectedAbi = "arm64-v8a", expectedMajorVersion = 21)
            assertTrue("Expected Bionic runtime to verify, got: ${okResult.diagnosticMessage}", okResult.isVerified)
            assertTrue(okResult.isAndroidBionic)
            assertEquals("21.0.5", okResult.detectedJavaVersion)

            // 4. Failing execution probe rejects runtime
            val failingVerifier = AndroidRuntimeVerifier { _, _ -> "DLOPEN_FAILED: cannot locate symbol \"foo\"" }
            val failedExec = failingVerifier.verify(bionicHome, expectedAbi = "arm64-v8a", expectedMajorVersion = 21)
            assertFalse(failedExec.isVerified)
            assertTrue(failedExec.diagnosticMessage.contains("EXECUTION_FAILED"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun sourceResolverMatchesAndroidMultiarchAndTermuxAptButBlocksDesktopGlibc() = runTest {
        assertTrue(
            AndroidRuntimeSourceResolver.isDisallowedDesktopBuild(
                "https://api.adoptium.net/v3/binary/latest/21/ga/linux/aarch64/jre/hotspot/normal/eclipse",
                "Temurin Linux",
            )
        )
        assertFalse(
            AndroidRuntimeSourceResolver.isDisallowedDesktopBuild(
                "https://github.com/MojoLauncher/android-openjdk-build-17-25/releases/download/21/jre21-arm64-release.tar.xz",
                "Android OpenJDK 21",
            )
        )

        val packagesText = """
            Package: openjdk-21
            Version: 21.0.5-1
            Architecture: aarch64
            Depends: zlib, libc++, bash, ca-certificates-java
            Filename: pool/main/o/openjdk-21/openjdk-21_21.0.5-1_aarch64.deb
            Size: 123456
            SHA256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef

            Package: zlib
            Version: 1.3.1
            Architecture: aarch64
            Filename: pool/main/z/zlib/zlib_1.3.1_aarch64.deb
            Size: 55000
            SHA256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

            Package: libc++
            Version: 27c
            Architecture: aarch64
            Depends: zlib
            Filename: pool/main/libc++/libc++_27c_aarch64.deb
            Size: 99000
            SHA256: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb

            Package: bash
            Version: 5.2
            Architecture: aarch64
            Filename: pool/main/b/bash/bash_5.2_aarch64.deb
            Size: 40000
            SHA256: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
        """.trimIndent()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        try {
            server.createContext("/repos/test/releases") { ex ->
                val payload = """
                    [
                      {
                        "tag_name": "jre21-2025.02",
                        "name": "Android OpenJDK 21",
                        "assets": [
                          {
                            "name": "jre21-arm64-20250201-release.tar.xz",
                            "browser_download_url": "http://127.0.0.1:$port/dl/jre21-arm64-20250201-release.tar.xz",
                            "size": 45000000
                          },
                          {
                            "name": "jre21-x86_64-20250201-release.tar.xz",
                            "browser_download_url": "http://127.0.0.1:$port/dl/jre21-x86_64-20250201-release.tar.xz",
                            "size": 45000000
                          }
                        ]
                      }
                    ]
                """.trimIndent().toByteArray()
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            }
            server.createContext("/termux/dists/stable/main/binary-aarch64/Packages") { ex ->
                val payload = packagesText.toByteArray()
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            }
            server.createContext("/termux/dists/stable/main/binary-aarch64/Packages.xz") { ex ->
                ex.sendResponseHeaders(404, -1)
            }
            server.createContext("/termux/dists/stable/main/binary-aarch64/Packages.gz") { ex ->
                ex.sendResponseHeaders(404, -1)
            }
            server.start()

            val resolver = AndroidRuntimeSourceResolver(
                okHttpClient = OkHttpClient(),
                json = json,
                endpoints = RuntimeRepositoryEndpoints(
                    githubReleasesUrls = listOf("http://127.0.0.1:$port/repos/test/releases"),
                    termuxAptRepoBaseUrls = listOf("http://127.0.0.1:$port/termux"),
                ),
            )
            val arm64Abi = AndroidRuntimeSourceResolver.detectDeviceAbiInfo(listOf("arm64-v8a", "armeabi-v7a"))
            assertTrue(arm64Abi.isArm64Compatible)
            assertEquals("AArch64", arm64Abi.elfMachineName)

            val resolved = resolver.resolvePackages(21, arm64Abi)
            assertEquals(2, resolved.size)
            assertTrue(resolved[0].downloadUrl.contains("jre21-arm64-20250201-release.tar.xz"))
            val termuxPkg = resolved[1]
            assertEquals(RuntimeArchiveFormat.DEB, termuxPkg.format)
            assertEquals(listOf("zlib", "libc++"), termuxPkg.companionPackages.map { it.packageName })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun archiveExtractorExtractsDebAndBlocksZipSlip() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "noxer_extract_${System.nanoTime()}").apply { mkdirs() }
        try {
            // 1. Build a .deb (`ar` archive containing `data.tar.gz`) with nested Termux JVM layout
            val nestedTarGz = createRuntimeTarGz(
                entries = mapOf(
                    "data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/bin/java" to
                        buildTestElf64(0xB7, "/system/bin/linker64", listOf("libc.so")),
                    "data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/lib/server/libjvm.so" to
                        buildTestElf64(0xB7, neededLibs = listOf("libc.so", "libz.so.1")),
                    "data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/lib/modules" to byteArrayOf(4, 5, 6),
                    "data/data/com.termux/files/usr/lib/libz.so.1" to byteArrayOf(7, 8, 9),
                ),
                rootDirName = ".",
            )
            val debFile = File(tmpDir, "openjdk-21.deb")
            debFile.writeBytes(createArDeb(nestedTarGz))

            val staging = File(tmpDir, "staging")
            ArchiveExtractor.extractArchive(debFile, RuntimeArchiveFormat.DEB, staging, stripSingleRootDir = false)
            ArchiveExtractor.normalizeExtractedRuntimeLayout(staging)

            assertTrue(File(staging, "bin/java").exists())
            assertTrue(File(staging, "lib/server/libjvm.so").exists())
            assertTrue(File(staging, "lib/modules").exists())
            // Companion library preserved inside runtime/lib/
            assertTrue(File(staging, "lib/libz.so.1").exists())

            // 2. Zip-Slip protection check
            val evilTar = File(tmpDir, "evil.tar.gz")
            evilTar.writeBytes(createRawTarGz(listOf("root/../../escaped.txt" to "evil".toByteArray())))
            var blocked = false
            try {
                ArchiveExtractor.extractArchive(evilTar, RuntimeArchiveFormat.TAR_GZ, File(tmpDir, "safe_out"))
            } catch (_: SecurityException) {
                blocked = true
            }
            assertTrue("Expected Zip-Slip traversal to throw SecurityException", blocked)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun minecraft12111PlayAutomaticallyInstallsVerifiesAssignsAndLaunchesJava21() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NoxerDatabase::class.java).allowMainThreadQueries().build()
        val paths = NoxerPaths(context)
        val logger = ConsoleLogger(db.consoleLogDao(), paths)
        val okHttp = OkHttpClient()

        // Create genuine Android Bionic Java 21 ARM64 .tar.gz archive served by local HTTP server
        val archiveBytes = createRuntimeTarGz(
            entries = mapOf(
                "release" to "JAVA_VERSION=\"21.0.5\"\nOS_NAME=\"Android\"\nOS_ARCH=\"aarch64\"\n".toByteArray(),
                "bin/java" to buildTestElf64(0xB7, "/system/bin/linker64", listOf("libc.so", "libjli.so")),
                "lib/modules" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
                "lib/server/libjvm.so" to buildTestElf64(0xB7, neededLibs = listOf("libc.so", "libdl.so", "libm.so")),
            ),
            rootDirName = "android-openjdk-21-arm64",
        )
        val archiveSha256 = sha256Hex(archiveBytes)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        val rangeHeaderSeen = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            server.createContext("/jre21-arm64.tar.gz") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                if (range != null && range.startsWith("bytes=")) {
                    rangeHeaderSeen.set(true)
                    val start = range.removePrefix("bytes=").substringBefore("-").toIntOrNull() ?: 0
                    val slice = archiveBytes.copyOfRange(start, archiveBytes.size)
                    ex.responseHeaders.add("Content-Range", "bytes $start-${archiveBytes.size - 1}/${archiveBytes.size}")
                    ex.sendResponseHeaders(206, slice.size.toLong())
                    ex.responseBody.use { it.write(slice) }
                } else {
                    ex.sendResponseHeaders(200, archiveBytes.size.toLong())
                    ex.responseBody.use { it.write(archiveBytes) }
                }
            }
            server.start()

            // Seed interrupted .part file to also test resumable runtime download!
            val partDir = File(paths.runtimesDir, ".downloads/android-openjdk-21-arm64-v8a").apply { mkdirs() }
            File(partDir, "jre21-arm64.tar.gz.part").writeBytes(archiveBytes.copyOfRange(0, archiveBytes.size / 3))

            val arm64Abi = AndroidAbiInfo(
                primaryAbi = "arm64-v8a",
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
                isArm64Compatible = true,
                elfMachineName = "AArch64",
                pojavArchAliases = listOf("arm64", "aarch64"),
                termuxArch = "aarch64",
            )
            val testResolver = object : RuntimeSourceResolver {
                override fun detectAbiInfo(): AndroidAbiInfo = arm64Abi
                override suspend fun resolvePackages(majorVersion: Int, abi: AndroidAbiInfo) =
                    if (majorVersion == 21) {
                        listOf(
                            com.example.noxer.runtime.ResolvedRuntimePackage(
                                majorVersion = 21,
                                fullVersion = "21.0.5",
                                displayName = "Android OpenJDK 21 (arm64-v8a)",
                                providerName = "TestAndroidBionicRepo",
                                targetAbi = "arm64-v8a",
                                elfMachineExpected = "AArch64",
                                downloadUrl = "http://127.0.0.1:$port/jre21-arm64.tar.gz",
                                sha256 = archiveSha256,
                                sizeBytes = archiveBytes.size.toLong(),
                                format = RuntimeArchiveFormat.TAR_GZ,
                            )
                        )
                    } else {
                        emptyList()
                    }
            }

            val runtimeManager = JavaRuntimeManager(
                okHttpClient = okHttp,
                json = json,
                runtimeDao = db.runtimeDao(),
                paths = paths,
                consoleLogger = logger,
                installationDao = db.installationDao(),
                sourceResolver = testResolver,
                executionProbe = RuntimeExecutionProbe { libJvmPath, javaHome ->
                    assertTrue(File(libJvmPath).exists())
                    assertTrue(File(javaHome, "lib/modules").exists())
                    "OK: Executed JVM 21.0.5 (OpenJDK 64-Bit Server VM, aarch64)"
                },
            )

            // Prepare Minecraft 1.21.11 metadata + client JAR
            val mcVersion = "1.21.11"
            val clientJarBytes = "PK\u0003\u0004minecraft-1.21.11-client-payload".toByteArray()
            val clientJarFile = paths.versionJarFile(mcVersion).apply {
                parentFile?.mkdirs()
                writeBytes(clientJarBytes)
            }
            val details = VersionDetails(
                id = mcVersion,
                type = "release",
                mainClass = "net.minecraft.client.main.Main",
                javaVersion = JavaVersionInfo(component = "java-runtime-delta", majorVersion = 21),
                downloads = VersionDownloads(
                    client = DownloadArtifact(
                        sha1 = sha1Hex(clientJarBytes),
                        size = clientJarBytes.size.toLong(),
                        url = "http://127.0.0.1:$port/client.jar",
                    )
                ),
            )
            paths.versionJsonFile(mcVersion).writeText(json.encodeToString(VersionDetails.serializer(), details))

            val settingsManager = SettingsManager(context)
            val offlineManager = OfflineProfileManager(db.offlineProfileDao(), logger)
            val profile = offlineManager.createProfile("NoxerArm64", "Noxer", OfflineProfileIcon.SHIELD.id, "").getOrThrow()
            val authManager = MicrosoftAuthManager(okHttp, json, db.accountDao(), logger)
            val sessionManager = AccountSessionManager(authManager, offlineManager, settingsManager, logger)
            sessionManager.selectOfflineProfile(profile.id)

            val inst = InstallationEntity(
                id = "inst_1_21_11",
                name = "Survival 1.21.11",
                minecraftVersionId = mcVersion,
                javaRuntimeId = "auto",
                gameDirPath = paths.instanceGameDir("survival_1_21_11").absolutePath,
                status = InstallationStatus.READY.name,
            )
            db.installationDao().upsert(inst)

            // Assert NO Java 21 is installed beforehand
            assertNull(db.runtimeDao().getVerifiedByMajorVersion(21))

            var launchedLibJvm: String? = null
            var launchedMainClass: String? = null
            var launchedJvmOpts: List<String> = emptyList()
            val launcher = GameLauncher(
                context = context,
                paths = paths,
                installationDao = db.installationDao(),
                runtimeDao = db.runtimeDao(),
                runtimeManager = runtimeManager,
                authManager = authManager,
                offlineProfileManager = offlineManager,
                accountSessionManager = sessionManager,
                downloadEngine = DownloadEngine(okHttp, logger),
                modLoaderManager = ModLoaderManager(okHttp, json, paths, DownloadEngine(okHttp, logger), logger),
                rendererManager = RendererManager(context),
                crashHandler = CrashHandler(db.crashReportDao(), paths),
                consoleLogger = logger,
                json = json,
                jvmBackend = JvmLauncherBackend { libJvmPath, jvmOptions, mainClass, _, _ ->
                    launchedLibJvm = libJvmPath
                    launchedMainClass = mainClass
                    launchedJvmOpts = jvmOptions.toList()
                    0
                },
                startViewportActivity = false,
            )

            // Press PLAY -> must autonomously install, verify, assign Java 21, and continue launch!
            launcher.executePipeline(inst.id)

            assertTrue(
                "Resumable HTTP Range should have been used (error=${launcher.state.value.errorMessage})",
                rangeHeaderSeen.get(),
            )
            val installedJava21 = db.runtimeDao().getVerifiedByMajorVersion(21)
            assertNotNull("Java 21 must be registered and verified", installedJava21)
            assertTrue(installedJava21!!.isInstalled)
            assertTrue(installedJava21.isVerified)
            assertEquals("arm64-v8a", installedJava21.abi)

            // Verify installation was automatically assigned to Java 21 runtime
            val updatedInst = db.installationDao().getById(inst.id)!!
            assertEquals(installedJava21.id, updatedInst.javaRuntimeId)

            // Verify environment & JVM launch plan
            val plan = launcher.currentPlan!!
            assertEquals(installedJava21.installPath, plan.envVars["JAVA_HOME"])
            assertTrue(plan.envVars["LD_LIBRARY_PATH"].orEmpty().contains(installedJava21.installPath))
            assertTrue(plan.envVars["PATH"].orEmpty().contains("bin"))
            assertEquals(installedJava21.libJvmPath, launchedLibJvm)
            assertEquals("net.minecraft.client.main.Main", launchedMainClass)
            assertTrue(launchedJvmOpts.any { it.contains("-Djava.home=${installedJava21.installPath}") })
            assertEquals("Session ended cleanly (exit 0)", launcher.state.value.stepTitle)
            assertNull(launcher.state.value.errorMessage)

            // Deleting runtime cleanly clears installation reference back to "auto"
            runtimeManager.deleteRuntime(installedJava21)
            assertNull(db.runtimeDao().getById(installedJava21.id))
            assertEquals("auto", db.installationDao().getById(inst.id)?.javaRuntimeId)
            clientJarFile.delete()
        } finally {
            server.stop(0)
            db.close()
        }
    }

    @Test
    fun insufficientStorageAndCorruptChecksumNeverRegisterBrokenRuntime() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NoxerDatabase::class.java).allowMainThreadQueries().build()
        val paths = NoxerPaths(context)
        val logger = ConsoleLogger(db.consoleLogDao(), paths)
        val arm64Abi = AndroidRuntimeSourceResolver.detectDeviceAbiInfo(listOf("arm64-v8a"))

        val fakePkg = com.example.noxer.runtime.ResolvedRuntimePackage(
            majorVersion = 17,
            fullVersion = "17.0.12",
            displayName = "Android OpenJDK 17 (arm64-v8a)",
            providerName = "TestRepo",
            targetAbi = "arm64-v8a",
            elfMachineExpected = "AArch64",
            downloadUrl = "http://127.0.0.1:9/unused.tar.gz",
            sha256 = "0".repeat(64),
            sizeBytes = 50_000_000L,
            format = RuntimeArchiveFormat.TAR_GZ,
        )
        val resolver = object : RuntimeSourceResolver {
            override fun detectAbiInfo() = arm64Abi
            override suspend fun resolvePackages(majorVersion: Int, abi: AndroidAbiInfo) = listOf(fakePkg)
        }

        val lowStorageManager = JavaRuntimeManager(
            okHttpClient = OkHttpClient(),
            json = json,
            runtimeDao = db.runtimeDao(),
            paths = paths,
            consoleLogger = logger,
            sourceResolver = resolver,
            availableBytesProvider = { 10L * 1024L * 1024L }, // Only 10 MB free
        )

        val progressUpdates = mutableListOf<RuntimeInstallProgress>()
        val result = lowStorageManager.fetchAndInstallRuntime(17) { progressUpdates += it }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Insufficient storage"))
        assertTrue("Broken/failed install must never be stored in RuntimeDao", db.runtimeDao().getAll().isEmpty())
        assertTrue("Version 1.21.11 infers Java 21", VersionCenterRepository.inferRequiredJavaMajor("1.21.11", null) == 21)
        db.close()
    }

    @Test
    fun playAutoRepairsNonReadyInstallationAndConfiguresAndroidNettyAndBionicNatives() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NoxerDatabase::class.java).allowMainThreadQueries().build()
        val paths = NoxerPaths(context)
        val logger = ConsoleLogger(db.consoleLogDao(), paths)
        val okHttp = OkHttpClient()

        // Rule evaluation normalization check for aarch64 / arm64 / arm64-v8a
        assertTrue(
            MojangRuleEvaluator.isAllowed(
                rules = listOf(RuleEntry(action = "allow", os = OsRule(name = "linux", arch = "aarch64"))),
                hostArch = "arm64-v8a",
            )
        )
        assertFalse(
            MojangRuleEvaluator.isAllowed(
                rules = listOf(RuleEntry(action = "allow", os = OsRule(name = "linux", arch = "x86_64"))),
                hostArch = "arm64-v8a",
            )
        )

        val mcVersion = "26.3"
        val clientJarBytes = "PK\u0003\u0004minecraft-26.3-jar".toByteArray()
        val clientSha1 = sha1Hex(clientJarBytes)
        val assetIndexBytes = """{"objects":{}}""".toByteArray()
        val assetIndexSha1 = sha1Hex(assetIndexBytes)

        // Pre-seed cached valid client.jar (0 missing base JARs) while install status is DOWNLOADING (reproducing screenshot bug!)
        val clientJarFile = paths.versionJarFile(mcVersion).apply {
            parentFile?.mkdirs()
            writeBytes(clientJarBytes)
        }

        // Build a mixed natives JAR containing 1 glibc desktop lib (must be rejected), 1 libglfw.so (must NOT shadow APK bridge), and 1 valid Bionic AArch64 .so (must be kept)
        val nativesJarBaos = ByteArrayOutputStream()
        ZipOutputStream(nativesJarBaos).use { zos ->
            zos.putNextEntry(ZipEntry("libopenal.so"))
            zos.write(buildTestElf64(0xB7, neededLibs = listOf("libc.so", "libm.so")))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("libdesktop_bad.so"))
            zos.write(buildTestElf64(0xB7, neededLibs = listOf("libc.so.6")))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("libglfw.so"))
            zos.write(buildTestElf64(0xB7, neededLibs = listOf("libc.so")))
            zos.closeEntry()
        }
        val nativesJarBytes = nativesJarBaos.toByteArray()
        val nativesJarSha1 = sha1Hex(nativesJarBytes)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        try {
            server.createContext("/indexes/26.json") { ex ->
                ex.sendResponseHeaders(200, assetIndexBytes.size.toLong())
                ex.responseBody.use { it.write(assetIndexBytes) }
            }
            server.createContext("/lwjgl-natives.jar") { ex ->
                ex.sendResponseHeaders(200, nativesJarBytes.size.toLong())
                ex.responseBody.use { it.write(nativesJarBytes) }
            }
            server.start()

            // Register verified Java 25 runtime on disk
            val rtDir = paths.runtimeDir("android-openjdk-25-arm64-v8a").apply { mkdirs() }
            File(rtDir, "lib/server").mkdirs()
            val libJvm = File(rtDir, "lib/server/libjvm.so").apply {
                writeBytes(buildTestElf64(0xB7, neededLibs = listOf("libc.so", "libdl.so")))
            }
            db.runtimeDao().upsert(
                com.example.noxer.data.db.RuntimeEntity(
                    id = "android-openjdk-25-arm64-v8a",
                    displayName = "Android OpenJDK 25 (arm64-v8a)",
                    majorVersion = 25,
                    fullVersion = "25.0.0",
                    abi = "arm64-v8a",
                    installPath = rtDir.absolutePath,
                    libJvmPath = libJvm.absolutePath,
                    javaBinPath = "",
                    sourceProvider = "TestRepo",
                    isDefault = true,
                    sha256 = "0".repeat(64),
                    sizeBytes = 1024L,
                    downloadUrl = "http://127.0.0.1:$port/rt.tar.gz",
                    isInstalled = true,
                    isVerified = true,
                    diagnosticMessage = "OK",
                )
            )

            val details = VersionDetails(
                id = mcVersion,
                type = "release",
                mainClass = "net.minecraft.client.main.Main",
                javaVersion = JavaVersionInfo(component = "java-runtime-gamma", majorVersion = 25),
                assetIndex = AssetIndexInfo(
                    id = "26",
                    sha1 = assetIndexSha1,
                    size = assetIndexBytes.size.toLong(),
                    url = "http://127.0.0.1:$port/indexes/26.json",
                ),
                downloads = VersionDownloads(
                    client = DownloadArtifact(
                        sha1 = clientSha1,
                        size = clientJarBytes.size.toLong(),
                        url = "http://127.0.0.1:$port/client.jar",
                    )
                ),
                libraries = listOf(
                    LibraryEntry(
                        name = "org.lwjgl:lwjgl:3.3.3:natives-linux",
                        downloads = LibraryDownloads(
                            artifact = DownloadArtifact(
                                path = "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar",
                                sha1 = nativesJarSha1,
                                size = nativesJarBytes.size.toLong(),
                                url = "http://127.0.0.1:$port/lwjgl-natives.jar",
                            )
                        ),
                    )
                ),
            )
            paths.versionJsonFile(mcVersion).apply {
                parentFile?.mkdirs()
                writeText(json.encodeToString(VersionDetails.serializer(), details))
            }

            val settingsManager = SettingsManager(context)
            val offlineManager = OfflineProfileManager(db.offlineProfileDao(), logger)
            val profile = offlineManager.createProfile("NoxerPlayer", "Player", OfflineProfileIcon.SHIELD.id, "").getOrThrow()
            val authManager = MicrosoftAuthManager(okHttp, json, db.accountDao(), logger)
            val sessionManager = AccountSessionManager(authManager, offlineManager, settingsManager, logger)
            sessionManager.selectOfflineProfile(profile.id)

            val gameDir = paths.instanceGameDir("mc_26_3")
            val inst = InstallationEntity(
                id = "inst_26_3",
                name = "Minecraft 26.3",
                minecraftVersionId = mcVersion,
                javaRuntimeId = "android-openjdk-25-arm64-v8a",
                gameDirPath = gameDir.absolutePath,
                status = InstallationStatus.DOWNLOADING.name,
            )
            db.installationDao().upsert(inst)

            val arm64Abi = AndroidRuntimeSourceResolver.detectDeviceAbiInfo(listOf("arm64-v8a"))
            val runtimeManager = JavaRuntimeManager(
                okHttpClient = okHttp,
                json = json,
                runtimeDao = db.runtimeDao(),
                paths = paths,
                consoleLogger = logger,
                installationDao = db.installationDao(),
                sourceResolver = object : RuntimeSourceResolver {
                    override fun detectAbiInfo() = arm64Abi
                    override suspend fun resolvePackages(majorVersion: Int, abi: AndroidAbiInfo) = emptyList<com.example.noxer.runtime.ResolvedRuntimePackage>()
                },
            )

            var launchedOpts: List<String> = emptyList()
            val launcher = GameLauncher(
                context = context,
                paths = paths,
                installationDao = db.installationDao(),
                runtimeDao = db.runtimeDao(),
                runtimeManager = runtimeManager,
                authManager = authManager,
                offlineProfileManager = offlineManager,
                accountSessionManager = sessionManager,
                downloadEngine = DownloadEngine(okHttp, logger),
                modLoaderManager = ModLoaderManager(okHttp, json, paths, DownloadEngine(okHttp, logger), logger),
                rendererManager = RendererManager(context),
                crashHandler = CrashHandler(db.crashReportDao(), paths),
                consoleLogger = logger,
                json = json,
                jvmBackend = JvmLauncherBackend { _, opts, _, _, _ ->
                    launchedOpts = opts.toList()
                    0
                },
                startViewportActivity = false,
            )

            launcher.executePipeline(inst.id)

            assertNull("Should auto-repair & launch without '0 missing and 0 corrupted' failure", launcher.state.value.errorMessage)
            assertEquals("Session ended cleanly (exit 0)", launcher.state.value.stepTitle)

            val healedInst = db.installationDao().getById(inst.id)!!
            assertEquals(InstallationStatus.READY.name, healedInst.status)

            // Verify natives extraction filtered out glibc & libglfw.so while keeping valid Bionic libopenal.so
            val nativesDir = File(gameDir, "natives")
            assertTrue(File(nativesDir, "libopenal.so").exists())
            assertFalse(File(nativesDir, "libdesktop_bad.so").exists())
            assertFalse(File(nativesDir, "libglfw.so").exists())

            // Verify critical Android LWJGL + Netty NIO multiplayer flags are configured
            assertTrue(launchedOpts.contains("-Dio.netty.transport.noNative=true"))
            assertTrue(launchedOpts.contains("-Dorg.lwjgl.opengl.libname=libGL.so"))
            assertTrue(launchedOpts.contains("-Dorg.lwjgl.glfw.libname=libglfw.so"))
            assertTrue(launchedOpts.any { it.startsWith("-Djava.io.tmpdir=") })

            // Verify LaunchExecutionPlan JSON roundtrip
            val samplePlan = LaunchExecutionPlan(
                installationId = inst.id,
                installationName = inst.name,
                minecraftVersionId = mcVersion,
                gameDirPath = gameDir.absolutePath,
                authModeLabel = "OFFLINE",
                runtimeId = "android-openjdk-25-arm64-v8a",
                runtimeMajorVersion = 25,
                runtimeAbi = "arm64-v8a",
                libJvmPath = libJvm.absolutePath,
                mainClass = "net.minecraft.client.main.Main",
                jvmOptions = launchedOpts,
                gameArgs = listOf("--username", "NoxerPlayer"),
                envVars = mapOf("JAVA_HOME" to rtDir.absolutePath),
                commandPreview = "preview",
                logFilePath = File(gameDir, "logs/noxer-game-latest.log").absolutePath,
            )
            val encoded = json.encodeToString(LaunchExecutionPlan.serializer(), samplePlan)
            val decoded = json.decodeFromString(LaunchExecutionPlan.serializer(), encoded)
            assertEquals(samplePlan, decoded)

            clientJarFile.delete()
        } finally {
            server.stop(0)
            db.close()
        }
    }
}
