package io.github.alagga.gonesmart

import android.app.Application
import android.os.Environment
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Bounded read-only discovery of GMMP's installed Equalizer preset files.
 *
 * GMMP has historically persisted presets under gmmp/eq_preset and also ships
 * default presets that are installed/restored by the app. We inspect the real
 * GMMP-owned storage (plus matching packaged entries) so users do not need to
 * open every preset once merely for GoneSmart to learn its curve.
 */
object GmmpEqualizerPresetCatalog {
    private const val MAX_PRESET_BYTES = 128 * 1024L
    private const val MAX_FILES_PER_DIRECTORY = 128
    private const val MAX_DISCOVERED_DIRECTORIES = 32
    private const val MAX_DIRECTORY_SCAN_NODES = 512
    private const val MAX_DIRECTORY_SCAN_DEPTH = 5
    private const val MAX_APK_CANDIDATES = 128

    data class Entry(
        val preset: GmmpEqualizerPresetParser.ParsedPreset,
        val source: String
    )

    data class Result(
        val entries: List<Entry>,
        val candidateCount: Int,
        val rejectedCount: Int
    )

    fun discover(application: Application): Result {
        val accepted = linkedMapOf<String, Entry>()
        var candidateCount = 0
        var rejectedCount = 0

        fun accept(nameHint: String, bytes: ByteArray, source: String) {
            candidateCount += 1
            val parsed = GmmpEqualizerPresetParser.parse(nameHint, bytes)
            if (parsed == null) {
                rejectedCount += 1
                return
            }
            val key = parsed.name.trim().lowercase(Locale.US)
            if (key.isNotBlank()) accepted.putIfAbsent(key, Entry(parsed, source))
        }

        val directories = candidateDirectories(application)
        directories.take(MAX_DISCOVERED_DIRECTORIES).forEach { directory ->
            directory.listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isFile && it.length() in 1..MAX_PRESET_BYTES }
                .sortedBy { it.name.lowercase(Locale.US) }
                .take(MAX_FILES_PER_DIRECTORY)
                .forEach { file ->
                    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                    accept(file.name, bytes, "file:${file.absolutePath}")
                }
        }

        applicationApkPaths(application).forEach { apkPath ->
            runCatching {
                ZipFile(apkPath).use { zip ->
                    zip.entries().asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.size in 1..MAX_PRESET_BYTES &&
                                looksLikePresetPath(entry.name)
                        }
                        .take(MAX_APK_CANDIDATES)
                        .forEach { entry ->
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            accept(entry.name.substringAfterLast('/'), bytes, "apk:${entry.name}")
                        }
                }
            }
        }

        return Result(
            entries = accepted.values.sortedBy { it.preset.name.lowercase(Locale.US) },
            candidateCount = candidateCount,
            rejectedCount = rejectedCount
        )
    }

    private fun candidateDirectories(application: Application): List<File> {
        val ordered = linkedSetOf<File>()

        @Suppress("DEPRECATION")
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { external ->
            ordered += File(external, "gmmp/eq_preset")
        }

        ordered += File(application.applicationInfo.dataDir, "files/gmmp/eq_preset")
        ordered += File(application.applicationInfo.dataDir, "files/eq_preset")
        ordered += File(application.applicationInfo.dataDir, "eq_preset")

        runCatching { application.getExternalFilesDirs(null).filterNotNull() }
            .getOrDefault(emptyList())
            .forEach { externalFiles ->
                ordered += File(externalFiles, "gmmp/eq_preset")
                ordered += File(externalFiles, "eq_preset")
            }

        // Current GMMP versions are free to move the files. Search only GMMP's
        // own private roots, with strict node/depth bounds, for semantically
        // named preset directories. This is startup-only and never polls.
        val privateRoots = listOf(
            File(application.applicationInfo.dataDir),
            application.filesDir,
            application.noBackupFilesDir
        ).distinctBy { it.absolutePath }
        privateRoots.forEach { root ->
            discoverNamedPresetDirectories(root).forEach(ordered::add)
        }

        return ordered
            .filter { it.isDirectory && it.canRead() }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    private fun discoverNamedPresetDirectories(root: File): List<File> {
        if (!root.isDirectory || !root.canRead()) return emptyList()
        data class Node(val file: File, val depth: Int)
        val queue = ArrayDeque<Node>()
        val found = mutableListOf<File>()
        queue.add(Node(root, 0))
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_DIRECTORY_SCAN_NODES && found.size < MAX_DISCOVERED_DIRECTORIES) {
            val node = queue.removeFirst()
            visited += 1
            val name = node.file.name.lowercase(Locale.US)
            if (looksLikePresetDirectory(name)) found += node.file
            if (node.depth >= MAX_DIRECTORY_SCAN_DEPTH) continue
            node.file.listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isDirectory && it.canRead() }
                .take(64)
                .forEach { queue.add(Node(it, node.depth + 1)) }
        }
        return found
    }

    private fun looksLikePresetDirectory(name: String): Boolean {
        val normalized = name.replace('-', '_').replace(' ', '_')
        return normalized == "eq_preset" ||
            normalized == "eq_presets" ||
            normalized == "equalizer_preset" ||
            normalized == "equalizer_presets"
    }

    private fun looksLikePresetPath(path: String): Boolean {
        val lower = path.lowercase(Locale.US)
        return lower.contains("eq_preset") ||
            lower.contains("eq-presets") ||
            ((lower.contains("equalizer") || lower.contains("/eq/")) && lower.contains("preset"))
    }

    private fun applicationApkPaths(application: Application): List<String> = buildList {
        add(application.applicationInfo.sourceDir)
        application.applicationInfo.splitSourceDirs?.let(::addAll)
    }.distinct()
}
