package io.github.alagga.gonesmart

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.util.concurrent.Executors

/**
 * GMMP 4.2.0 move orchestrator: durable private stage -> ORIGINAL py0.b
 * confirmation/worker removes old indexed M3Us -> verify physical AND
 * native-index removal -> publish staged normalized M3Us -> ORIGINAL t6.f
 * scanner registers their paths -> verify native-index insertion -> cleanup.
 *
 * No direct GMMP DB writes, no blind File.renameTo(source,target), and no
 * deletion of an original without a durable per-playlist backup.
 */
internal class NativeGmmpPlaylistMover(
    private val hostLoader: ClassLoader
) {
    private companion object {
        const val TAG = "GoneSmartPlaylist"
        const val POLL_MS = 700L
    }
    private enum class Phase {
        STAGING, WAIT_DELETE, PUBLISH, WAIT_INDEX, RESTORING, WAIT_ROLLBACK
    }
    private class Pending(
        val batch: PlaylistMoveStager.Batch,
        val context: Context,
        var paths: () -> List<String>?,
        val onResult: (Boolean, String) -> Unit,
        var phase: Phase = Phase.WAIT_DELETE,
        var polling: Boolean = false,
        var deleteDialogDismissed: Boolean = false,
        var dialogSeen: Boolean = false,
        var autoConfirmed: Boolean = false,
        var dismissedAtMs: Long? = null,
        var polls: Int = 0
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GoneSmart-PlaylistMove").apply { isDaemon = true }
    }
    private var pending: Pending? = null
    private var awaitingNativeDialog: Pending? = null
    private var nativeDelete: java.lang.reflect.Method? = null
    private var nativeFileCtor: java.lang.reflect.Constructor<*>? = null
    private var nativeScan: java.lang.reflect.Method? = null

    private fun genericListElementClass(
        method: Method,
        parameterIndex: Int
    ): Class<*>? {
        val type = method.genericParameterTypes
            .getOrNull(parameterIndex) as? ParameterizedType
            ?: return null
        val argument = type.actualTypeArguments.singleOrNull() ?: return null
        return when (argument) {
            is Class<*> -> argument
            is ParameterizedType -> argument.rawType as? Class<*>
            else -> null
        }
    }

    private fun resolveNativeMethods(): Boolean {
        if (nativeDelete != null && nativeScan != null &&
            nativeFileCtor != null) return true
        return runCatching {
            val fileType = hostLoader.loadClass("th1")
            val ctor = runCatching {
                fileType.getDeclaredConstructor(
                    File::class.java, java.lang.Long::class.java
                )
            }.getOrNull() ?: fileType.declaredConstructors.filter {
                it.parameterCount == 2 &&
                    File::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    ) &&
                    (it.parameterTypes[1] == java.lang.Long::class.java ||
                        it.parameterTypes[1] == java.lang.Long.TYPE)
            }.singleOrNull() ?: error(
                "GMMP playlist file wrapper constructor is not unique"
            )

            val deleteType = hostLoader.loadClass("py0")
            val deleteCandidates = deleteType.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 2 &&
                    Context::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    ) &&
                    java.util.List::class.java.isAssignableFrom(
                        it.parameterTypes[1]
                    )
            }
            val genericWrapperMatches = deleteCandidates.filter {
                genericListElementClass(it, 1)?.let(fileType::isAssignableFrom) ==
                    true
            }
            val delete = runCatching {
                deleteType.getDeclaredMethod(
                    "b", Context::class.java, java.util.List::class.java
                )
            }.getOrNull()
                ?: genericWrapperMatches.singleOrNull()
                ?: deleteCandidates.singleOrNull()
                ?: error(
                    "GMMP playlist delete method is not structurally unique: " +
                        deleteCandidates.joinToString(",") { method ->
                            method.name + "<" +
                                (genericListElementClass(method, 1)?.name
                                    ?: "?") + ">"
                        }
                )

            val scanType = hostLoader.loadClass("t6")
            val scanner = runCatching {
                scanType.getDeclaredMethod(
                    "f", Context::class.java, Array<String>::class.java
                )
            }.getOrNull() ?: scanType.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 2 &&
                    Context::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    ) &&
                    it.parameterTypes[1].isArray &&
                    it.parameterTypes[1].componentType ==
                        String::class.java
            }.singleOrNull() ?: error(
                "GMMP playlist scanner method is not structurally unique"
            )

            delete.isAccessible = true
            ctor.isAccessible = true
            scanner.isAccessible = true
            require(Modifier.isStatic(delete.modifiers) &&
                Modifier.isStatic(scanner.modifiers))
            if (delete.name != "b" || scanner.name != "f") {
                Log.i(
                    TAG,
                    "PLAYLIST MOVE MAPPING | delete=" +
                        delete.declaringClass.name + "." + delete.name +
                        " | deleteElement=" +
                        (genericListElementClass(delete, 1)?.name ?: "erased") +
                        " | wrapper=" + fileType.name +
                        " | scanner=" +
                        scanner.declaringClass.name + "." + scanner.name
                )
            }
            nativeDelete = delete
            nativeFileCtor = ctor
            nativeScan = scanner
            true
        }.onFailure {
            Log.w(
                TAG,
                "PLAYLIST MOVE | original delete/scan not verified; blocked",
                it
            )
        }.getOrDefault(false)
    }

    /**
     * Returns false before any mutation if the version-specific original
     * native deletion or scanner signatures cannot be established.
     */
    fun start(
        context: Context,
        plan: PlaylistMovePolicy.Plan,
        currentPaths: () -> List<String>?,
        onResult: (Boolean, String) -> Unit
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (pending != null || !resolveNativeMethods()) return false
        if (!PlaylistMovePolicy.sourcesStillMatch(
                plan, currentPaths() ?: return false
            )
        ) return false
        // Reserve the operation before starting disk IO on a background
        // thread. Another user action must not start an overlapping move.
        val reservation = Pending(
            PlaylistMoveStager.Batch(File(""),plan.root,emptyList(),0L),
            context, currentPaths, onResult, Phase.STAGING
        )
        pending = reservation
        worker.execute {
            val staged = runCatching {
                PlaylistMoveStager.stage(plan, context.filesDir)
            }
            main.post {
                if (pending !== reservation) {
                    staged.getOrNull()?.let(PlaylistMoveStager::finish)
                    return@post
                }
                val batch = staged.getOrElse { error ->
                    pending = null
                    Log.e(TAG, "PLAYLIST MOVE | durable staging failed", error)
                    onResult(false, "Could not safely stage playlists")
                    return@post
                }
                val snapshot = currentPaths()
                if (snapshot == null ||
                    !PlaylistMovePolicy.sourcesStillMatch(plan, snapshot)
                ) {
                    PlaylistMoveStager.finish(batch)
                    pending = null
                    onResult(false, "Playlist list changed; move cancelled")
                    return@post
                }
                val task = Pending(batch, context, currentPaths, onResult)
                pending = task
                val native = runCatching {
                    val files = ArrayList<Any>(batch.entries.size)
                    batch.entries.forEach {
                        files += requireNotNull(nativeFileCtor)
                            .newInstance(it.source, null)
                    }
                    awaitingNativeDialog = task
                    try {
                        requireNotNull(nativeDelete).invoke(null, context, files)
                    } finally {
                        awaitingNativeDialog = null
                    }
                }
                if (native.isFailure) {
                    Log.e(TAG, "PLAYLIST MOVE | original confirmation unavailable",
                        native.exceptionOrNull())
                    pending = null
                    PlaylistMoveStager.finish(batch)
                    onResult(false, "GMMP native confirmation unavailable")
                } else {
                    Log.i(TAG, "PLAYLIST MOVE | durable backups ready; " +
                        "native delete transaction requested | count=" +
                        batch.entries.size)
                    schedule(task)
                }
            }
        }
        return true
    }

    /**
     * The installed GMMP 4.2.0 APK contains MaterialDialogs v3, NOT the
     * former v0.9 DialogAction/getActionButton API. Its original v3
     * WhichButton.POSITIVE and MaterialDialog.onActionButtonClicked$core
     * are verified against the actual private GMMP base.apk DEX.
     *
     * Our own destination Select action is the user's confirmation, so
     * consume only the exact native dialog whose show() runs synchronously
     * inside the CURRENT native py0.b move request. Invoke the host
     * MaterialDialog's ORIGINAL positive-action dispatcher before it
     * attaches a window. This executes its own original GMMP callback
     * and DeletePlaylistFileWorker, without briefly showing a DELETE
     * prompt. Ordinary GMMP and GoneSmart folder-delete dialogs proceed
     * through their unmodified original show() path.
     *
     * All reflection and positive-listener preflight completes BEFORE
     * invoking any native callback; any mismatch falls back to the
     * visible ORIGINAL GMMP confirmation rather than guessing an action.
     */
    fun onNativeDialogBeforeShow(dialog: Dialog): Boolean {
        val task = awaitingNativeDialog ?: return false
        if (pending !== task || task.dialogSeen) return false
        val nativeAction = runCatching {
            val dialogClass = hostLoader.loadClass(
                "com.afollestad.materialdialogs.MaterialDialog"
            )
            require(dialogClass.isInstance(dialog)) {
                "Not the original GMMP MaterialDialog"
            }
            val whichClass = hostLoader.loadClass(
                "com.afollestad.materialdialogs.WhichButton"
            )
            val positive = whichClass.enumConstants?.firstOrNull {
                (it as? Enum<*>)?.name == "POSITIVE"
            } ?: error("Original v3 WhichButton.POSITIVE absent")
            // This is a real, registered GMMP delete action, not a bare
            // dialog whose button label happens to say Delete.
            val listeners = dialogClass.getDeclaredField(
                "positiveListeners"
            ).apply { isAccessible = true }.get(dialog) as? List<*>
            require(!listeners.isNullOrEmpty()) {
                "Native positive delete callback was not registered"
            }
            val dispatcher = dialogClass.getDeclaredMethod(
                "onActionButtonClicked\$core", whichClass
            ).apply { isAccessible = true }
            dispatcher to positive
        }.onFailure { error ->
            Log.w(
                TAG,
                "PLAYLIST MOVE | original v3 positive action unverified; " +
                    "original GMMP confirmation remains visible",
                error
            )
        }.getOrNull() ?: return false

        // Disarm the one-shot scope BEFORE executing GMMP's own native
        // callback, so a nested/unrelated native dialog is never consumed.
        awaitingNativeDialog = null
        task.dialogSeen = true
        task.deleteDialogDismissed = true
        task.dismissedAtMs = android.os.SystemClock.elapsedRealtime()
        task.polls = 0
        runCatching {
            nativeAction.first.invoke(dialog, nativeAction.second)
        }.onSuccess {
            task.autoConfirmed = true
            Log.i(
                TAG,
                "PLAYLIST MOVE | original GMMP v3 POSITIVE callback " +
                    "invoked without showing its deletion dialog"
            )
        }.onFailure { error ->
            // The native callback may have partially enqueued its worker.
            // Never show it again: that risks duplicate deletion. Let
            // the durable staged move's original index/restore watchdog
            // finish or roll back from the verified source-file state.
            Log.e(
                TAG,
                "PLAYLIST MOVE | v3 callback threw; second confirmation " +
                    "suppressed, staged originals retained for verification",
                error
            )
        }
        return true
    }

    /**
     * Safe fallback if the original v3 pre-show callback could not be
     * verified. This now uses the APK's ACTUAL static
     * DialogActionExtKt.getActionButton(MaterialDialog, WhichButton)
     * rather than the nonexistent old DialogAction API.
     */
    fun onNativeDialogShown(dialog: Dialog) {
        val task = awaitingNativeDialog ?: return
        val originalClass = runCatching {
            hostLoader.loadClass(
                "com.afollestad.materialdialogs.MaterialDialog"
            )
        }.getOrNull() ?: return
        if (!originalClass.isInstance(dialog)) return
        task.dialogSeen = true
        dialog.window?.decorView?.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    task.deleteDialogDismissed = true
                    task.dismissedAtMs =
                        android.os.SystemClock.elapsedRealtime()
                    task.polls = 0
                    Log.i(TAG, "PLAYLIST MOVE | native confirmation dismissed")
                }
            }
        )
        val clicked = runCatching {
            val whichClass = hostLoader.loadClass(
                "com.afollestad.materialdialogs.WhichButton"
            )
            val positive = whichClass.enumConstants?.firstOrNull {
                (it as? Enum<*>)?.name == "POSITIVE"
            } ?: error("Native WhichButton.POSITIVE missing")
            val actions = hostLoader.loadClass(
                "com.afollestad.materialdialogs.actions.DialogActionExtKt"
            )
            val getter = actions.getDeclaredMethod(
                "getActionButton", originalClass, whichClass
            ).apply { isAccessible = true }
            val button = getter.invoke(null, dialog, positive) as? View
                ?: error("Original v3 positive button unavailable")
            require(
                button.isShown && button.isEnabled &&
                    button.hasOnClickListeners()
            ) { "Original v3 positive button not clickable" }
            button.performClick()
        }.onFailure {
            Log.w(
                TAG,
                "PLAYLIST MOVE | original v3 action button unavailable; " +
                    "GMMP confirmation remains visible",
                it
            )
        }.getOrDefault(false)
        task.autoConfirmed = clicked
        if (clicked) {
            Log.i(
                TAG,
                "PLAYLIST MOVE | original GMMP v3 positive action invoked " +
                    "after native dialog show (fallback)"
            )
        }
    }

    /**
     * When a new GMMP native playlist adapter attaches (including after
     * process death), resume ONLY a previously staged operation belonging
     * to its exact canonical root. Never re-show a deletion prompt.
     */
    fun resume(
        context: Context,
        nativeRoot: File,
        currentPaths: () -> List<String>?
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { resume(context, nativeRoot, currentPaths) }
            return
        }
        pending?.let { active ->
            if (active.batch.root == runCatching {
                    nativeRoot.canonicalFile
                }.getOrNull()
            ) {
                // Replace an old fragment's weak supplier after recreation.
                // Never leave a successful original delete waiting forever
                // on a detached RecyclerView.
                active.paths = currentPaths
            }
            return
        }
        if (!resolveNativeMethods()) return
        val staged = PlaylistMoveStager.recover(context.filesDir, nativeRoot)
        val batch = staged.firstOrNull { candidate ->
            candidate.entries.any { !it.source.exists() || it.target.exists() }
        } ?: return
        // If all old files still exist, this was a canceled prior dialog.
        // Retain its private backups (not extra user-visible playlists) until
        // separately cleaned rather than guessing whether a worker is queued.
        if (batch.entries.all { it.source.exists() }) return
        val task = Pending(batch, context, currentPaths,
            { success, message ->
                Log.i(TAG, "PLAYLIST MOVE RECOVERY | " +
                    (if (success) "completed" else "pending") +
                    " | " + message)
            })
        pending = task
        Log.i(TAG, "PLAYLIST MOVE | recovering durable staged batch")
        schedule(task)
    }

    private fun scan(context: Context, files: Collection<File>): Boolean =
        runCatching {
            requireNotNull(nativeScan).invoke(
                null, context, files.map { it.absolutePath }.toTypedArray()
            )
            true
        }.onFailure {
            Log.e(TAG, "PLAYLIST MOVE | GMMP original t6.f scan unavailable", it)
        }.getOrDefault(false)

    /**
     * If native bulk removal stopped halfway, or publication met a NEW
     * target collision, put every missing original back from its private
     * durable backup, ask GMMP's original scanner to reindex the originals,
     * and wait for their actual native adapter records before cleaning up.
     */
    private fun rollback(task: Pending) {
        if (task.phase == Phase.RESTORING ||
            task.phase == Phase.WAIT_ROLLBACK
        ) return
        task.phase = Phase.RESTORING
        worker.execute {
            val restored = PlaylistMoveStager.restoreMissingOriginals(task.batch)
            main.post {
                if (pending !== task) return@post
                if (!restored) {
                    pending = null
                    task.onResult(
                        false, "Recovery blocked; private backup retained"
                    )
                    Log.e(TAG, "PLAYLIST MOVE | original restore failed; " +
                        "private originals retained")
                    return@post
                }
                task.phase = Phase.WAIT_ROLLBACK
                task.polls = 0
                scan(task.context, task.batch.entries.map { it.source })
                Log.w(TAG, "PLAYLIST MOVE | restoring original native index")
                schedule(task)
            }
        }
    }

    private fun schedule(task: Pending) {
        if (task.polling) return
        task.polling = true
        main.postDelayed({
            task.polling = false
            if (pending === task) check(task)
        }, POLL_MS)
    }

    private fun check(task: Pending) {
        val native = task.paths()?.mapNotNull { path ->
            runCatching { File(path).canonicalPath }.getOrNull()
        }?.toSet()
        if (native == null) {
            schedule(task)
            return
        }
        val oldGone = task.batch.entries.all {
            !it.source.exists() && it.source.path !in native
        }
        val targetIndexed = task.batch.entries.all {
            it.target.isFile && it.target.path in native &&
                runCatching {
                    PlaylistMoveStager.hash(it.target.readBytes()) ==
                        it.normalizedHash
                }.getOrDefault(false)
        }
        if (task.phase == Phase.WAIT_ROLLBACK) {
            val restoredIndexed = task.batch.entries.all {
                it.source.isFile && it.source.path in native
            }
            if (restoredIndexed) {
                PlaylistMoveStager.finish(task.batch)
                pending = null
                task.onResult(false, "Move cancelled; originals restored")
                Log.w(TAG, "PLAYLIST MOVE | rollback verified in original index")
                return
            }
            task.polls++
            if (task.polls % 60 == 0) {
                scan(task.context, task.batch.entries.map { it.source })
            }
            if (task.polls >= 360) {
                pending = null
                task.onResult(false, "Restore indexing pending; backup retained")
                return
            }
            schedule(task)
            return
        }
        if (targetIndexed && oldGone) {
            PlaylistMoveStager.finish(task.batch)
            pending = null
            task.onResult(true, "Playlists moved")
            Log.i(TAG, "PLAYLIST MOVE | original DB removal and " +
                "destination registration verified | count=" +
                task.batch.entries.size)
            return
        }
        if (oldGone && task.phase != Phase.PUBLISH &&
            task.phase != Phase.WAIT_INDEX
        ) {
            // The process may have died after publishing but BEFORE the
            // original GMMP t6.f rescan. Reuse only our byte-identical
            // already-published target files; never overwrite a conflict.
            val publishedAlready = task.batch.entries.all { entry ->
                entry.target.isFile && runCatching {
                    PlaylistMoveStager.hash(entry.target.readBytes()) ==
                        entry.normalizedHash
                }.getOrDefault(false)
            }
            if (publishedAlready) {
                task.phase = Phase.WAIT_INDEX
                task.polls = 0
                scan(task.context, task.batch.entries.map { it.target })
                schedule(task)
                return
            }
            task.phase = Phase.PUBLISH
            task.polls = 0
            worker.execute {
                val result = PlaylistMoveStager.commit(task.batch)
                main.post {
                    if (pending !== task) return@post
                    if (!result) {
                        Log.e(TAG, "PLAYLIST MOVE | publication blocked;" +
                            " restoring originals from durable backup")
                        rollback(task)
                        return@post
                    }
                    task.phase = Phase.WAIT_INDEX
                    task.polls = 0
                    scan(task.context, task.batch.entries.map { it.target })
                    Log.i(TAG, "PLAYLIST MOVE | original t6.f rescan requested")
                    schedule(task)
                }
            }
            return
        }
        task.polls++
        if (task.phase == Phase.WAIT_INDEX &&
            task.polls % 60 == 0
        ) {
            scan(task.context, task.batch.entries.map { it.target })
        }
        if (task.phase == Phase.WAIT_DELETE &&
            task.deleteDialogDismissed &&
            task.polls >= 240 &&
            task.batch.entries.any { !it.source.exists() }
        ) {
            Log.w(TAG, "PLAYLIST MOVE | partial native deletion; " +
                "rolling back to original playlists")
            rollback(task)
            return
        }
        if (task.polls % 20 == 0) {
            Log.i(TAG, "PLAYLIST MOVE | awaiting native index" +
                " | phase=" + task.phase +
                " | remainingOld=" +
                task.batch.entries.count { it.source.exists() })
        }
        if (NativePlaylistMoveTimeoutPolicy.isAbandoned(
                task.dismissedAtMs,
                android.os.SystemClock.elapsedRealtime(),
                originalsStillPresent = task.batch.entries.all {
                    it.source.exists() && it.source.path in native
                }
            )
        ) {
            PlaylistMoveStager.markCancelled(task.batch)
            pending = null
            task.onResult(false,
                "Native deletion not confirmed; originals unchanged")
            Log.i(TAG, "PLAYLIST MOVE | original confirmation cancelled " +
                "or native deletion not completed; private stage abandoned")
            return
        }
        if (NativePlaylistMoveTimeoutPolicy.hasTimedOut(
                task.polls,
                waitingForUserConfirmation =
                    task.phase == Phase.WAIT_DELETE &&
                    task.dialogSeen && !task.deleteDialogDismissed
            )
        ) {
            pending = null
            task.onResult(false, "Native scan pending; safe backup retained")
            Log.w(TAG, "PLAYLIST MOVE | indexing timeout; " +
                "durable backup retained for later recovery")
            return
        }
        schedule(task)
    }
}
