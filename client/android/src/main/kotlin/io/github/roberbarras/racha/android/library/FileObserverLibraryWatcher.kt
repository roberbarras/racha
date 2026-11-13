package io.github.roberbarras.racha.android.library

import android.os.Build
import android.os.FileObserver
import io.github.roberbarras.racha.application.library.scan.LibraryWatcher
import io.github.roberbarras.racha.files.NioLibrarySource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * Native file watching on Android (FileObserver = inotify). FileObserver is not recursive, so every directory gets
 * its own observer and new directories are picked up as they appear. Only active while the flow is collected.
 */
class FileObserverLibraryWatcher : LibraryWatcher {

    override fun changes(location: String): Flow<Unit> = callbackFlow {
        val observers = mutableMapOf<String, FileObserver>()
        fun watch(dir: File) {
            if (!dir.isDirectory || dir.path in observers) return
            val observer = observerFor(dir) { event, name ->
                val child = name?.let { File(dir, it) }
                if (child != null && event and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0 && child.isDirectory) {
                    synchronized(observers) { child.walkTopDown().filter(File::isDirectory).forEach(::watch) }
                }
                trySend(Unit)
            }
            observers[dir.path] = observer
            observer.startWatching()
        }
        val root = NioLibrarySource.pathOf(location).toFile()
        synchronized(observers) { root.walkTopDown().filter(File::isDirectory).forEach(::watch) }
        awaitClose { synchronized(observers) { observers.values.forEach(FileObserver::stopWatching) } }
    }

    private fun observerFor(dir: File, onEvent: (Int, String?) -> Unit): FileObserver {
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
            FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent(event, path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.path, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent(event, path)
            }
        }
    }
}
