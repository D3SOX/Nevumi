package me.d3sox.nevumi.library

import me.d3sox.nevumi.artwork.ArtworkLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchKey
import java.util.concurrent.TimeUnit

class WatchingLocalFiles(private val library: MusicLibrary, artwork: ArtworkLibrary? = null) : LocalSources {
    private val scanner = LocalFiles(library, artwork)
    override suspend fun roots(): List<String> = (scanner.roots() + library.localFolderRules().included).distinct()
    override suspend fun pendingRefreshLocations(): List<String> =
        (scanner.pendingRefreshLocations() + (roots() - scanner.roots().toSet())).distinct()
    override suspend fun scan(location: String, force: Boolean, onProgress: suspend (LocalScanProgress) -> Unit): List<Track> = scanner.scan(location, force, onProgress)
    override suspend fun scanSong(location: String): List<Track> = scanner.scanSong(location)

    override val changes: Flow<Unit> = flow {
        FileSystems.getDefault().newWatchService().use { watcher ->
            val directories = mutableMapOf<Path, WatchKey>()
            fun registerTree(root: Path): Boolean {
                if (!Files.isDirectory(root)) return false
                var added = false
                Files.walk(root).use { paths ->
                    paths.filter { Files.isDirectory(it) }.forEach { directory ->
                        if (directory !in directories) {
                            directories[directory] = directory.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                            added = true
                        }
                    }
                }
                return added
            }
            while (currentCoroutineContext().isActive) {
                var registered = false
                roots().forEach { root ->
                    val path = Path.of(root)
                    if (path !in directories && registerTree(path)) registered = true
                }
                if (registered && scanner.pendingRefreshLocations().isNotEmpty()) emit(Unit)
                val key = watcher.poll(1, TimeUnit.SECONDS) ?: continue
                val directory = directories.entries.firstOrNull { it.value == key }?.key
                val events = key.pollEvents()
                if (directory != null) events.filter { it.kind() == ENTRY_CREATE }.forEach { event ->
                    (event.context() as? Path)?.let { registerTree(directory.resolve(it)) }
                }
                if (!key.reset()) directories.entries.removeAll { it.value == key }
                if (events.isNotEmpty()) emit(Unit)
            }
        }
    }.flowOn(Dispatchers.IO)
}
