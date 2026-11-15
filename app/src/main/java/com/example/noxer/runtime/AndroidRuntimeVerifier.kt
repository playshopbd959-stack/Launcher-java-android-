package com.example.noxer.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

data class ElfInspection(
    val isValidElf: Boolean,
    val machineName: String,
    val interpreter: String?,
    val neededLibraries: List<String>,
    val hasGlibcDependencies: Boolean,
)

/**
 * Performs strict Android-compatibility, ELF ABI/linker inspection, and real execution verification
 * for an extracted Java runtime directory.
 *
 * Never approves desktop glibc builds (`ld-linux-*`, `libc.so.6`, `libpthread.so.0`) and never
 * marks a runtime verified unless real execution (either `bin/java -version` or isolated
 * `JNI_CreateJavaVM`) succeeds.
 */
class AndroidRuntimeVerifier(
    private val executionProbe: RuntimeExecutionProbe,
) {
    suspend fun verify(
        javaHome: File,
        expectedAbi: String,
        expectedMajorVersion: Int,
    ): RuntimeVerificationResult {
        val libJvm = ArchiveExtractor.findLibJvm(javaHome)
            ?: return fail(
                javaHome = javaHome,
                message = "ERROR: libjvm.so not found inside ${javaHome.absolutePath}",
            )
        val javaBin = ArchiveExtractor.findJavaBinary(javaHome)

        val expectedMachine = when (expectedAbi) {
            "arm64-v8a" -> "AArch64"
            "x86_64" -> "x86-64"
            "armeabi-v7a" -> "ARM"
            "x86" -> "x86"
            else -> "AArch64"
        }

        val libJvmElf = inspectElf(libJvm)
        if (!libJvmElf.isValidElf) {
            return fail(
                javaHome = javaHome,
                libJvm = libJvm,
                javaBin = javaBin,
                elfMachine = libJvmElf.machineName,
                message = "ERROR: libjvm.so is not a valid ELF binary (${libJvmElf.machineName}).",
            )
        }
        if (libJvmElf.machineName != expectedMachine) {
            return fail(
                javaHome = javaHome,
                libJvm = libJvm,
                javaBin = javaBin,
                elfMachine = libJvmElf.machineName,
                message = "ARCH_MISMATCH: libjvm.so ELF architecture is ${libJvmElf.machineName}, device requires $expectedAbi ($expectedMachine).",
            )
        }
        if (libJvmElf.hasGlibcDependencies) {
            return fail(
                javaHome = javaHome,
                libJvm = libJvm,
                javaBin = javaBin,
                elfMachine = libJvmElf.machineName,
                isBionic = false,
                message = "GLIBC_INCOMPATIBLE: libjvm.so links against desktop Linux glibc (${describeGlibcDeps(libJvmElf)}) which cannot run on Android Bionic.",
            )
        }

        val javaBinElf = javaBin?.let { inspectElf(it) }
        if (javaBinElf != null && javaBinElf.isValidElf) {
            if (javaBinElf.machineName != expectedMachine) {
                return fail(
                    javaHome = javaHome,
                    libJvm = libJvm,
                    javaBin = javaBin,
                    elfMachine = javaBinElf.machineName,
                    message = "ARCH_MISMATCH: bin/java ELF architecture is ${javaBinElf.machineName}, device requires $expectedAbi ($expectedMachine).",
                )
            }
            if (javaBinElf.hasGlibcDependencies) {
                return fail(
                    javaHome = javaHome,
                    libJvm = libJvm,
                    javaBin = javaBin,
                    elfMachine = javaBinElf.machineName,
                    isBionic = false,
                    message = "GLIBC_INCOMPATIBLE: bin/java uses desktop Linux glibc (${describeGlibcDeps(javaBinElf)}) instead of Android /system/bin/linker.",
                )
            }
        }

        // Check core JDK/JRE module image / classes
        val hasCoreClasses = File(javaHome, "lib/modules").exists() ||
            File(javaHome, "lib/rt.jar").exists() ||
            File(javaHome, "jre/lib/rt.jar").exists()
        if (!hasCoreClasses) {
            return fail(
                javaHome = javaHome,
                libJvm = libJvm,
                javaBin = javaBin,
                elfMachine = libJvmElf.machineName,
                message = "INCOMPLETE_RUNTIME: Missing Java module image (lib/modules or rt.jar) in ${javaHome.absolutePath}.",
            )
        }

        // Parse `release` metadata if present to double-check major version
        val releaseVersion = readReleaseJavaVersion(javaHome)
        val env = buildRuntimeEnvironment(javaHome, libJvm, javaBin, nativesDir = null)

        // Ensure executable flags are set before testing execution
        ArchiveExtractor.configurePermissions(javaHome)

        // Execution Attempt A: try direct `bin/java -version` (works on API <= 28 or non-restricted hosts)
        val cliAttempt = javaBin?.let { tryRunJavaCli(it, env) }
        if (cliAttempt != null && cliAttempt.first) {
            val detected = parseVersionFromCliOutput(cliAttempt.second) ?: releaseVersion ?: "Java $expectedMajorVersion"
            return RuntimeVerificationResult(
                isVerified = true,
                elfMachine = libJvmElf.machineName,
                isAndroidBionic = true,
                javaHomePath = javaHome.absolutePath,
                libJvmPath = libJvm.absolutePath,
                javaBinPath = javaBin.absolutePath,
                detectedJavaVersion = detected,
                executionMethod = "bin/java -version",
                diagnosticMessage = "OK: Verified Android Bionic ${libJvmElf.machineName} via bin/java ($detected)",
            )
        }

        // Execution Attempt B: real JNI_CreateJavaVM in isolated process (required on Android API 29+ W^X SELinux)
        val jniResult = executionProbe.verifyInIsolatedProcess(libJvm.absolutePath, javaHome.absolutePath)
        if (jniResult.startsWith("OK:")) {
            val detected = jniResult.substringAfter("Executed JVM ", "").substringBefore(" (").trim()
                .ifBlank { releaseVersion ?: "Java $expectedMajorVersion" }
            return RuntimeVerificationResult(
                isVerified = true,
                elfMachine = libJvmElf.machineName,
                isAndroidBionic = true,
                javaHomePath = javaHome.absolutePath,
                libJvmPath = libJvm.absolutePath,
                javaBinPath = javaBin?.absolutePath.orEmpty(),
                detectedJavaVersion = detected,
                executionMethod = "JNI_CreateJavaVM",
                diagnosticMessage = "$jniResult • ELF ${libJvmElf.machineName} Android Bionic",
            )
        }

        val cliNote = cliAttempt?.second?.take(120)?.let { " | CLI attempt: $it" }.orEmpty()
        return fail(
            javaHome = javaHome,
            libJvm = libJvm,
            javaBin = javaBin,
            elfMachine = libJvmElf.machineName,
            message = "EXECUTION_FAILED: $jniResult$cliNote",
        )
    }

    private fun fail(
        javaHome: File,
        libJvm: File? = null,
        javaBin: File? = null,
        elfMachine: String = "Unknown",
        isBionic: Boolean = true,
        message: String,
    ) = RuntimeVerificationResult(
        isVerified = false,
        elfMachine = elfMachine,
        isAndroidBionic = isBionic,
        javaHomePath = javaHome.absolutePath,
        libJvmPath = libJvm?.absolutePath.orEmpty(),
        javaBinPath = javaBin?.absolutePath.orEmpty(),
        detectedJavaVersion = "",
        executionMethod = "failed",
        diagnosticMessage = message,
    )

    private fun tryRunJavaCli(javaBin: File, env: RuntimeEnvironment): Pair<Boolean, String> {
        return runCatching {
            val pb = ProcessBuilder(javaBin.absolutePath, "-version")
                .directory(env.javaHome)
                .redirectErrorStream(true)
            pb.environment().putAll(env.envVars)
            val proc = pb.start()
            val finished = proc.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return false to "CLI timed out after 8s"
            }
            val output = proc.inputStream.bufferedReader().readText().trim()
            val ok = proc.exitValue() == 0 && (
                output.contains("version", ignoreCase = true) ||
                    output.contains("openjdk", ignoreCase = true) ||
                    output.contains("java", ignoreCase = true)
                )
            ok to output
        }.getOrElse { err ->
            false to (err.message ?: "execve blocked")
        }
    }

    companion object {
        fun buildRuntimeEnvironment(
            javaHome: File,
            libJvm: File,
            javaBin: File?,
            nativesDir: File?,
        ): RuntimeEnvironment {
            val libDirs = linkedSetOf<String>().apply {
                libJvm.parentFile?.absolutePath?.let { add(it) }
                File(javaHome, "lib").takeIf { it.exists() }?.absolutePath?.let { add(it) }
                File(javaHome, "lib/jli").takeIf { it.exists() }?.absolutePath?.let { add(it) }
                File(javaHome, "jre/lib").takeIf { it.exists() }?.absolutePath?.let { add(it) }
                File(javaHome, "jre/lib/jli").takeIf { it.exists() }?.absolutePath?.let { add(it) }
                nativesDir?.takeIf { it.exists() }?.absolutePath?.let { add(it) }
            }.toList()

            val ldPath = libDirs.joinToString(":")
            val binDir = javaBin?.parentFile?.absolutePath ?: File(javaHome, "bin").absolutePath
            val existingPath = System.getenv("PATH").orEmpty()
            val fullPath = if (existingPath.isNotBlank()) "$binDir:$existingPath" else binDir

            val envMap = mapOf(
                "JAVA_HOME" to javaHome.absolutePath,
                "LD_LIBRARY_PATH" to ldPath,
                "PATH" to fullPath,
            )
            return RuntimeEnvironment(
                javaHome = javaHome,
                libJvmFile = libJvm,
                javaBinFile = javaBin,
                libraryPaths = libDirs,
                envVars = envMap,
            )
        }

        fun readReleaseJavaVersion(javaHome: File): String? {
            val releaseFile = File(javaHome, "release")
            if (!releaseFile.exists()) return null
            val line = releaseFile.readLines().firstOrNull { it.startsWith("JAVA_VERSION=") } ?: return null
            return line.substringAfter('=').trim().trim('"')
        }

        private fun parseVersionFromCliOutput(output: String): String? {
            val match = Regex("""version\s+"([^"]+)"""").find(output)
            return match?.groupValues?.getOrNull(1)
        }

        private fun describeGlibcDeps(elf: ElfInspection): String {
            val items = buildList {
                elf.interpreter?.let { add(it) }
                addAll(elf.neededLibraries.filter { isGlibcName(it) })
            }
            return items.distinct().joinToString(", ")
        }

        private fun isGlibcName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.contains("ld-linux") ||
                lower.contains("libc.so.6") ||
                lower.contains("libpthread.so.0") ||
                lower.contains("libdl.so.2") ||
                lower.contains("libm.so.6") ||
                lower.contains("librt.so.1") ||
                lower.contains("libc.musl")
        }

        fun inspectElf(file: File): ElfInspection {
            if (!file.exists() || !file.isFile || file.length() < 20) {
                return ElfInspection(false, "Invalid", null, emptyList(), false)
            }
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val len = raf.length()
                    val ident = ByteArray(20)
                    raf.readFully(ident)
                    if (ident[0] != 0x7F.toByte() || ident[1] != 'E'.code.toByte() ||
                        ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()
                    ) {
                        return@use ElfInspection(false, "Not-ELF", null, emptyList(), false)
                    }
                    val eiClass = ident[4].toInt() and 0xFF // 1 = ELF32, 2 = ELF64
                    val eiData = ident[5].toInt() and 0xFF  // 1 = LE, 2 = BE
                    val order = if (eiData == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN

                    val headerSize = if (eiClass == 2) 64 else 52
                    if (len < headerSize) {
                        val mach = readUShort(ident, 18, order)
                        return@use ElfInspection(true, mapMachine(mach), null, emptyList(), false)
                    }

                    val ehdr = ByteArray(headerSize)
                    raf.seek(0)
                    raf.readFully(ehdr)
                    val bb = ByteBuffer.wrap(ehdr).order(order)
                    val eMachine = bb.getShort(18).toInt() and 0xFFFF
                    val machineName = mapMachine(eMachine)

                    val ePhoff: Long
                    val eShoff: Long
                    val ePhentsize: Int
                    val ePhnum: Int
                    val eShentsize: Int
                    val eShnum: Int
                    if (eiClass == 2) {
                        ePhoff = bb.getLong(32)
                        eShoff = bb.getLong(40)
                        ePhentsize = bb.getShort(54).toInt() and 0xFFFF
                        ePhnum = bb.getShort(56).toInt() and 0xFFFF
                        eShentsize = bb.getShort(58).toInt() and 0xFFFF
                        eShnum = bb.getShort(60).toInt() and 0xFFFF
                    } else {
                        ePhoff = bb.getInt(28).toLong() and 0xFFFFFFFFL
                        eShoff = bb.getInt(32).toLong() and 0xFFFFFFFFL
                        ePhentsize = bb.getShort(42).toInt() and 0xFFFF
                        ePhnum = bb.getShort(44).toInt() and 0xFFFF
                        eShentsize = bb.getShort(46).toInt() and 0xFFFF
                        eShnum = bb.getShort(48).toInt() and 0xFFFF
                    }

                    // 1. Read PT_INTERP (p_type == 3) if present
                    var interpreter: String? = null
                    if (ePhoff > 0 && ePhnum in 1..128 && ePhentsize >= 32 && ePhoff + ePhnum.toLong() * ePhentsize <= len) {
                        for (i in 0 until ePhnum) {
                            val phBytes = ByteArray(ePhentsize)
                            raf.seek(ePhoff + i.toLong() * ePhentsize)
                            raf.readFully(phBytes)
                            val ph = ByteBuffer.wrap(phBytes).order(order)
                            val pType = ph.getInt(0)
                            if (pType == 3) { // PT_INTERP
                                val offset = if (eiClass == 2) ph.getLong(8) else ph.getInt(4).toLong() and 0xFFFFFFFFL
                                val filesz = if (eiClass == 2) ph.getLong(32) else ph.getInt(16).toLong() and 0xFFFFFFFFL
                                if (offset in 1 until len && filesz in 1..512 && offset + filesz <= len) {
                                    val strBytes = ByteArray(filesz.toInt())
                                    raf.seek(offset)
                                    raf.readFully(strBytes)
                                    interpreter = strBytes.decodeToString().trim('\u0000', ' ')
                                }
                                break
                            }
                        }
                    }

                    // 2. Read SHT_DYNAMIC (sh_type == 6) + linked .dynstr -> DT_NEEDED (d_tag == 1)
                    val needed = mutableListOf<String>()
                    if (eShoff > 0 && eShnum in 1..4096 && eShentsize >= 40 && eShoff + eShnum.toLong() * eShentsize <= len) {
                        for (i in 0 until eShnum) {
                            val shBytes = ByteArray(eShentsize)
                            raf.seek(eShoff + i.toLong() * eShentsize)
                            raf.readFully(shBytes)
                            val sh = ByteBuffer.wrap(shBytes).order(order)
                            val shType = sh.getInt(4)
                            if (shType == 6) { // SHT_DYNAMIC
                                val dynOffset = if (eiClass == 2) sh.getLong(24) else sh.getInt(16).toLong() and 0xFFFFFFFFL
                                val dynSize = if (eiClass == 2) sh.getLong(32) else sh.getInt(20).toLong() and 0xFFFFFFFFL
                                val dynLink = if (eiClass == 2) sh.getInt(40) else sh.getInt(24)

                                if (dynLink in 0 until eShnum && dynOffset > 0 && dynSize in 8..(1024L * 1024L) && dynOffset + dynSize <= len) {
                                    val strShBytes = ByteArray(eShentsize)
                                    raf.seek(eShoff + dynLink.toLong() * eShentsize)
                                    raf.readFully(strShBytes)
                                    val strSh = ByteBuffer.wrap(strShBytes).order(order)
                                    val strOffset = if (eiClass == 2) strSh.getLong(24) else strSh.getInt(16).toLong() and 0xFFFFFFFFL
                                    val strSize = if (eiClass == 2) strSh.getLong(32) else strSh.getInt(20).toLong() and 0xFFFFFFFFL

                                    if (strOffset > 0 && strSize in 1..(4L * 1024L * 1024L) && strOffset + strSize <= len) {
                                        val dynStr = ByteArray(strSize.toInt())
                                        raf.seek(strOffset)
                                        raf.readFully(dynStr)

                                        val dynBufBytes = ByteArray(dynSize.toInt())
                                        raf.seek(dynOffset)
                                        raf.readFully(dynBufBytes)
                                        val dynBuf = ByteBuffer.wrap(dynBufBytes).order(order)
                                        val entrySize = if (eiClass == 2) 16 else 8
                                        val count = dynSize.toInt() / entrySize
                                        for (d in 0 until count) {
                                            val tag = if (eiClass == 2) dynBuf.getLong(d * 16) else dynBuf.getInt(d * 8).toLong()
                                            val value = if (eiClass == 2) dynBuf.getLong(d * 16 + 8) else dynBuf.getInt(d * 8 + 4).toLong() and 0xFFFFFFFFL
                                            if (tag == 0L) break // DT_NULL
                                            if (tag == 1L && value in 0 until strSize) { // DT_NEEDED
                                                needed += readNullTerminated(dynStr, value.toInt())
                                            }
                                        }
                                    }
                                }
                                break
                            }
                        }
                    }

                    val hasGlibc = (interpreter != null && isGlibcName(interpreter)) ||
                        needed.any { isGlibcName(it) }

                    ElfInspection(
                        isValidElf = true,
                        machineName = machineName,
                        interpreter = interpreter,
                        neededLibraries = needed,
                        hasGlibcDependencies = hasGlibc,
                    )
                }
            }.getOrElse {
                ElfInspection(false, "Invalid", null, emptyList(), false)
            }
        }

        private fun readUShort(bytes: ByteArray, offset: Int, order: ByteOrder): Int =
            if (order == ByteOrder.BIG_ENDIAN) {
                ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            } else {
                (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            }

        private fun readNullTerminated(bytes: ByteArray, start: Int): String {
            var end = start
            while (end < bytes.size && bytes[end] != 0.toByte()) end++
            return String(bytes, start, end - start, Charsets.UTF_8)
        }

        private fun mapMachine(eMachine: Int): String = when (eMachine) {
            0xB7 -> "AArch64"
            0x3E -> "x86-64"
            0x28 -> "ARM"
            0x03 -> "x86"
            else -> "ELF(0x${eMachine.toString(16)})"
        }
    }
}
