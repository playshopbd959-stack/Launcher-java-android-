package com.example.noxer.runtime

import android.system.Os
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Extracts runtime archives (.tar.xz, .tar.gz, .zip, .deb) safely with Zip-Slip protection,
 * symlink/hardlink restoration, and POSIX executable permission configuration.
 */
object ArchiveExtractor {

    fun extractArchive(
        archive: File,
        format: RuntimeArchiveFormat,
        destDir: File,
        stripSingleRootDir: Boolean = true,
        onlyRelativeSubdirPrefixes: List<String>? = null,
    ) {
        destDir.mkdirs()
        when (format) {
            RuntimeArchiveFormat.TAR_XZ -> {
                BufferedInputStream(FileInputStream(archive)).use { raw ->
                    XZInputStream(raw).use { xz ->
                        extractTarStream(xz, destDir, stripSingleRootDir, onlyRelativeSubdirPrefixes)
                    }
                }
            }
            RuntimeArchiveFormat.TAR_GZ -> {
                BufferedInputStream(FileInputStream(archive)).use { raw ->
                    GzipCompressorInputStream(raw).use { gz ->
                        extractTarStream(gz, destDir, stripSingleRootDir, onlyRelativeSubdirPrefixes)
                    }
                }
            }
            RuntimeArchiveFormat.ZIP -> {
                extractZip(archive, destDir, stripSingleRootDir, onlyRelativeSubdirPrefixes)
            }
            RuntimeArchiveFormat.DEB -> {
                extractDebDataArchive(archive, destDir, onlyRelativeSubdirPrefixes)
            }
        }
    }

    private fun extractDebDataArchive(
        debFile: File,
        destDir: File,
        onlyRelativeSubdirPrefixes: List<String>?,
    ) {
        var extracted = false
        BufferedInputStream(FileInputStream(debFile)).use { raw ->
            ArArchiveInputStream(raw).use { ar ->
                var entry = ar.nextEntry
                while (entry != null) {
                    val name = entry.name.trim()
                    if (name.startsWith("data.tar")) {
                        when {
                            name.endsWith(".xz") -> {
                                XZInputStream(ar).use { xz ->
                                    extractTarStream(xz, destDir, stripSingleRootDir = false, onlyRelativeSubdirPrefixes)
                                }
                            }
                            name.endsWith(".gz") -> {
                                GzipCompressorInputStream(ar).use { gz ->
                                    extractTarStream(gz, destDir, stripSingleRootDir = false, onlyRelativeSubdirPrefixes)
                                }
                            }
                            name.endsWith(".bz2") -> {
                                BZip2CompressorInputStream(ar).use { bz2 ->
                                    extractTarStream(bz2, destDir, stripSingleRootDir = false, onlyRelativeSubdirPrefixes)
                                }
                            }
                            else -> {
                                extractTarStream(ar, destDir, stripSingleRootDir = false, onlyRelativeSubdirPrefixes)
                            }
                        }
                        extracted = true
                        break
                    }
                    entry = ar.nextEntry
                }
            }
        }
        if (!extracted) {
            throw IOException("Invalid .deb package ${debFile.name}: no data.tar* member found")
        }
    }

    private fun extractTarStream(
        input: InputStream,
        destDir: File,
        stripSingleRootDir: Boolean,
        onlyRelativeSubdirPrefixes: List<String>?,
    ) {
        val canonicalDest = destDir.canonicalPath
        val deferredLinks = mutableListOf<Triple<File, String, Boolean>>() // outFile, linkTarget, isHardLink
        val execFiles = mutableListOf<File>()

        TarArchiveInputStream(input).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val rawName = entry.name.trimStart('.', '/')
                val relName = if (stripSingleRootDir && rawName.contains('/')) {
                    rawName.substringAfter('/')
                } else {
                    rawName
                }
                if (relName.isNotBlank() && matchesPrefixes(relName, onlyRelativeSubdirPrefixes)) {
                    val outFile = safeResolve(destDir, canonicalDest, relName)
                    when {
                        entry.isDirectory -> outFile.mkdirs()
                        entry.isSymbolicLink -> {
                            deferredLinks += Triple(outFile, entry.linkName, false)
                        }
                        entry.isLink -> {
                            val cleanLink = if (stripSingleRootDir && entry.linkName.contains('/')) {
                                entry.linkName.trimStart('.', '/').substringAfter('/')
                            } else {
                                entry.linkName.trimStart('.', '/')
                            }
                            deferredLinks += Triple(outFile, cleanLink, true)
                        }
                        else -> {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                            val isExec = (entry.mode and 0b001001001) != 0 || isLikelyExecutablePath(outFile)
                            if (isExec) {
                                execFiles += outFile
                            }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }

        // Resolve symlinks and hardlinks after regular files have been extracted
        for ((outFile, linkTarget, isHardLink) in deferredLinks) {
            outFile.parentFile?.mkdirs()
            if (isHardLink) {
                val targetFile = safeResolve(destDir, canonicalDest, linkTarget)
                if (targetFile.exists()) {
                    runCatching { Os.link(targetFile.absolutePath, outFile.absolutePath) }
                        .onFailure {
                            if (targetFile.isFile) targetFile.copyTo(outFile, overwrite = true)
                        }
                }
            } else {
                createSymlinkOrCopyFallback(outFile, linkTarget, destDir, canonicalDest)
            }
        }

        for (file in execFiles) {
            applyExecutablePermissions(file)
        }
    }

    private fun extractZip(
        archive: File,
        destDir: File,
        stripSingleRootDir: Boolean,
        onlyRelativeSubdirPrefixes: List<String>?,
    ) {
        val canonicalDest = destDir.canonicalPath
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val rawName = entry.name.trimStart('.', '/')
                val relName = if (stripSingleRootDir && rawName.contains('/')) {
                    rawName.substringAfter('/')
                } else {
                    rawName
                }
                if (relName.isNotBlank() && matchesPrefixes(relName, onlyRelativeSubdirPrefixes)) {
                    val outFile = safeResolve(destDir, canonicalDest, relName)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> zip.copyTo(out) }
                        if (isLikelyExecutablePath(outFile)) {
                            applyExecutablePermissions(outFile)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /**
     * When Termux or multiarch tarballs wrap the JDK inside nested directories (e.g.
     * data/data/com.termux/files/usr/lib/jvm/java-21-openjdk), normalize its contents
     * directly under [runtimeRoot] so [runtimeRoot]/bin/java and [runtimeRoot]/lib/... exist,
     * while preserving companion shared libraries in [runtimeRoot]/lib/.
     */
    fun normalizeExtractedRuntimeLayout(stagingDir: File) {
        val libJvm = findLibJvm(stagingDir) ?: return
        val candidateJavaHome = inferJavaHomeFromLibJvm(libJvm, stagingDir) ?: return
        if (candidateJavaHome.canonicalPath == stagingDir.canonicalPath) {
            configurePermissions(stagingDir)
            return
        }

        // Preserve any companion .so files extracted outside candidateJavaHome (e.g. usr/lib/*.so*)
        val extraSharedLibs = stagingDir.walkTopDown()
            .filter { file ->
                (file.isFile || isSymlink(file)) &&
                    (file.name.endsWith(".so") || file.name.contains(".so.")) &&
                    !file.canonicalPath.startsWith(candidateJavaHome.canonicalPath)
            }
            .toList()

        val tempHome = File(stagingDir.parentFile, "${stagingDir.name}_norm_${System.currentTimeMillis()}")
        if (tempHome.exists()) tempHome.deleteRecursively()
        if (!candidateJavaHome.renameTo(tempHome)) {
            candidateJavaHome.copyRecursively(tempHome, overwrite = true)
            candidateJavaHome.deleteRecursively()
        }

        val targetLibDir = File(tempHome, "lib").apply { mkdirs() }
        for (extra in extraSharedLibs) {
            val destLib = File(targetLibDir, extra.name)
            if (!destLib.exists()) {
                runCatching { extra.copyTo(destLib, overwrite = false) }
            }
        }

        stagingDir.deleteRecursively()
        if (!tempHome.renameTo(stagingDir)) {
            tempHome.copyRecursively(stagingDir, overwrite = true)
            tempHome.deleteRecursively()
        }
        configurePermissions(stagingDir)
    }

    fun configurePermissions(javaHome: File) {
        if (!javaHome.exists()) return
        val binDir = File(javaHome, "bin")
        binDir.listFiles()?.forEach { f ->
            if (f.isFile) applyExecutablePermissions(f)
        }
        javaHome.walkTopDown().forEach { f ->
            if (f.isFile) {
                if (isLikelyExecutablePath(f) || f.name == "jspawnhelper" || f.name == "jexec") {
                    applyExecutablePermissions(f)
                }
            }
        }
    }

    fun findLibJvm(root: File): File? {
        if (!root.exists()) return null
        val preferred = listOf(
            File(root, "lib/server/libjvm.so"),
            File(root, "lib/aarch64/server/libjvm.so"),
            File(root, "lib/arm/server/libjvm.so"),
            File(root, "lib/amd64/server/libjvm.so"),
            File(root, "lib/i386/server/libjvm.so"),
            File(root, "jre/lib/aarch64/server/libjvm.so"),
            File(root, "jre/lib/arm/server/libjvm.so"),
            File(root, "jre/lib/amd64/server/libjvm.so"),
            File(root, "lib/client/libjvm.so"),
        ).firstOrNull { it.exists() && it.isFile }
        if (preferred != null) return preferred
        return root.walkTopDown().firstOrNull { it.isFile && it.name == "libjvm.so" }
    }

    fun findJavaBinary(javaHome: File): File? {
        val candidates = listOf(
            File(javaHome, "bin/java"),
            File(javaHome, "jre/bin/java"),
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
            ?: javaHome.walkTopDown().firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
    }

    private fun inferJavaHomeFromLibJvm(libJvm: File, boundary: File): File? {
        var cur: File? = libJvm.parentFile
        while (cur != null && cur.canonicalPath.startsWith(boundary.canonicalPath)) {
            if (File(cur, "bin/java").exists() || File(cur, "release").exists() || File(cur, "lib/modules").exists()) {
                return cur
            }
            if (cur.name == "lib" && File(cur.parentFile, "bin").exists()) {
                return cur.parentFile
            }
            if (cur.canonicalPath == boundary.canonicalPath) break
            cur = cur.parentFile
        }
        // Fallback: <javaHome>/lib/server/libjvm.so -> climb 3 levels
        return libJvm.parentFile?.parentFile?.parentFile?.takeIf {
            it.canonicalPath.startsWith(boundary.canonicalPath)
        } ?: boundary
    }

    private fun safeResolve(destDir: File, canonicalDest: String, relPath: String): File {
        val outFile = File(destDir, relPath)
        val outCanonical = outFile.canonicalPath
        if (outCanonical != canonicalDest && !outCanonical.startsWith("$canonicalDest${File.separator}")) {
            throw SecurityException("Blocked unsafe archive path: $relPath")
        }
        return outFile
    }

    private fun createSymlinkOrCopyFallback(
        outFile: File,
        linkTarget: String,
        destDir: File,
        canonicalDest: String,
    ) {
        if (outFile.exists()) outFile.delete()
        val resolvedTarget = if (linkTarget.startsWith("/")) {
            File(destDir, linkTarget.trimStart('/'))
        } else {
            File(outFile.parentFile, linkTarget)
        }
        val targetSafe = runCatching {
            val c = resolvedTarget.canonicalPath
            c == canonicalDest || c.startsWith("$canonicalDest${File.separator}")
        }.getOrDefault(false)

        if (!targetSafe) return

        val linked = runCatching {
            Os.symlink(linkTarget, outFile.absolutePath)
            true
        }.getOrDefault(false)

        if (!linked && resolvedTarget.exists() && resolvedTarget.isFile) {
            resolvedTarget.copyTo(outFile, overwrite = true)
            if (isLikelyExecutablePath(outFile)) {
                applyExecutablePermissions(outFile)
            }
        }
    }

    private fun matchesPrefixes(relPath: String, prefixes: List<String>?): Boolean {
        if (prefixes.isNullOrEmpty()) return true
        return prefixes.any { relPath.contains(it) }
    }

    private fun isLikelyExecutablePath(file: File): Boolean {
        val p = file.path
        return p.contains("/bin/") || file.name.endsWith(".so") || file.name.contains(".so.")
    }

    private fun applyExecutablePermissions(file: File) {
        file.setReadable(true, false)
        file.setExecutable(true, false)
        runCatching { Os.chmod(file.absolutePath, 493) } // 0755 octal
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { file.absolutePath != file.canonicalPath }.getOrDefault(false)
}
