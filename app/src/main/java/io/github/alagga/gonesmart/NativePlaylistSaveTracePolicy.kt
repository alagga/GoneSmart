package io.github.alagga.gonesmart

/**
 * Privacy-safe, read-only GMMP 4.2.0 save-call diagnostics.
 * Only method/class symbols are ever emitted: no File.toString(),
 * playlist names, original track paths, M3U contents, or user data.
 */
internal object NativePlaylistSaveTracePolicy {
    enum class Origin {
        MAIN_CREATE, PICKER_CREATE, EXISTING_PLAYLIST_FLOW, OTHER
    }

    private val excludedPackages = listOf(
        "java.", "javax.", "jdk.", "sun.", "android.", "androidx.",
        "kotlin.", "dalvik.", "libcore.", "org.lsposed.",
        "io.github.alagga.gonesmart."
    )

    fun origin(stack: Array<StackTraceElement>): Origin {
        val frames = visibleFrames(stack)
        return when {
            frames.any { it == "sp3.invoke" } -> Origin.MAIN_CREATE
            frames.any { it == "fo3.invoke" } -> Origin.PICKER_CREATE
            frames.any {
                it.startsWith("zp3.") || it.startsWith("io3.") ||
                    it.startsWith("yn3.")
            } -> Origin.EXISTING_PLAYLIST_FLOW
            else -> Origin.OTHER
        }
    }

    fun visibleFrames(
        stack: Array<StackTraceElement>,
        limit: Int = 18
    ): List<String> = stack.asSequence()
        .filter { frame ->
            excludedPackages.none { frame.className.startsWith(it) } &&
                frame.className != "java.lang.Thread" &&
                frame.methodName != "<clinit>"
        }
        .map { frame ->
            frame.className.substringAfterLast('.') + "." + frame.methodName
        }
        .distinct()
        .take(limit.coerceIn(0, 32))
        .toList()

    fun isGoneSmartScanner(stack: Array<StackTraceElement>): Boolean =
        stack.any {
            it.className.endsWith(".NativeGmmpPlaylistMover") &&
                it.methodName == "scan"
        }
}
