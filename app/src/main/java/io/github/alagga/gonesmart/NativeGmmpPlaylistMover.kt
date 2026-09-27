package io.github.alagga.gonesmart

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
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
        val paths: () -> List<String>?,
        val onResult: (Boolean, String) -> Unit,
        var phase: Phase = Phase.WAIT_DELETE,
        var polling: Boolean = false,
        var deleteDialogDismissed: Boolean = false,
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

    private fun resolveNativeMethods(): Boolean {
        if (nativeDelete != null && nativeScan != null &&
            nativeFileCtor != null) return true
        return runCatching {
            val delete = hostLoader.loadClass("py0").getDeclaredMethod(
                "b", Context::class.java, java.util.List::class.java
            ).apply { isAccessible = true }
            val ctor = hostLoader.loadClass("th1").getDeclaredConstructor(
                File::class.java, java.lang.Long::class.java
            ).apply { isAccessible = true }
            val scanner = hostLoader.loadClass("t6").getDeclaredMethod(
                "f", Context::class.java, Array<String>::class.java
            ).apply { isAccessible = true }
            require(Modifier.isStatic(delete.modifiers) &&
                Modifier.isStatic(scanner.modifiers))
            nativeDelete = delete
            nativeFileCtor = ctor
            nativeScan = scanner
            true
        }.onFailure {
            Log.w(TAG, "PLAYLIST MOVE | original delete/scan not verified; blocked", it)
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
                        "original GMMP confirmation opened | count=" +
                        batch.entries.size)
                    schedule(task)
                }
            }
        }
        return true
    }

    /**
     * Observe only the exact ORIGINAL MaterialDialog synchronously opened
     * from this move's original py0.b invocation. Never alter its delete
     * callback or positive button; cancellation leaves source untouched.
     */
    fun onNativeDialogShown(dialog: Dialog) {
        val task = awaitingNativeDialog ?: return
        val expected = runCatching {
            hostLoader.loadClass(
                "com.afollestad.materialdialogs.MaterialDialog"
            ).isInstance(dialog)
        }.getOrDefault(false)
        if (!expected) return
        dialog.window?.decorView?.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    task.deleteDialogDismissed = true
                    Log.i(TAG, "PLAYLIST MOVE | native confirmation dismissed")
                }
            }
        )
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
        if (pending != null || !resolveNativeMethods()) return
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
                scan(task.context, task.batch.entries.map { it.target })
                schedule(task)
                return
            }
            task.phase = Phase.PUBLISH
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
        if (task.polls >= 45 &&
            task.deleteDialogDismissed &&
            task.batch.entries.all { it.source.exists() } &&
            task.batch.entries.all { it.source.path in native }
        ) {
            pending = null
            task.onResult(false,
                "Native deletion not confirmed; originals unchanged")
            Log.i(TAG, "PLAYLIST MOVE | original confirmation cancelled " +
                "or native deletion not completed; backup retained")
            return
        }
        if (task.polls >= 360) {
            pending = null
            task.onResult(false, "Native scan pending; safe backup retained")
            Log.w(TAG, "PLAYLIST MOVE | indexing timeout; " +
                "durable backup retained for later recovery")
            return
        }
        schedule(task)
    }
}
