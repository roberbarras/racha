package io.github.roberbarras.racha.desktop.library

import io.github.roberbarras.racha.application.library.scan.LibraryWatcher
import io.github.roberbarras.racha.files.NioLibrarySource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import kotlin.io.path.isDirectory

/**
 * Native file watching on Linux (WatchService = inotify): every directory under the root is registered, new
 * directories are registered as they appear. One blocked daemon thread per watched root; no polling.
 */
class WatchServiceLibraryWatcher : LibraryWatcher {

    override fun changes(location: String): Flow<Unit> = callbackFlow {
        val root = NioLibrarySource.pathOf(location)
        val service = FileSystems.getDefault().newWatchService()
        val keys = mutableMapOf<WatchKey, Path>()
        fun register(dir: Path) {
            runCatching {
                Files.walk(dir).use { stream ->
                    stream.filter { it.isDirectory() }.forEach { d -> keys[d.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)] = d }
                }
            }
        }
        if (root.isDirectory()) register(root)
        val thread = Thread({ pump(service, keys, ::register) { trySend(Unit) } }, "racha-watch-${root.fileName}").apply {
            isDaemon = true
            start()
        }
        awaitClose {
            service.close()
            thread.interrupt()
        }
    }

    private fun pump(service: WatchService, keys: MutableMap<WatchKey, Path>, register: (Path) -> Unit, emit: () -> Unit) {
        try {
            while (true) {
                val key = service.take()
                val dir = keys[key]
                key.pollEvents().forEach { event ->
                    val changed = dir?.resolve(event.context() as Path)
                    if (event.kind() == ENTRY_CREATE && changed != null && changed.isDirectory()) register(changed)
                }
                if (!key.reset()) keys.remove(key)
                emit()
            }
        } catch (_: ClosedWatchServiceException) {
            // flow collector cancelled
        } catch (_: InterruptedException) {
            // flow collector cancelled
        }
    }
}
