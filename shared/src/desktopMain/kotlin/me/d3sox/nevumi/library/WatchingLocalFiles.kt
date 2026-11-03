package me.d3sox.nevumi.library

import me.d3sox.nevumi.artwork.ArtworkLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.NoSuchFileException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchKey
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

class WatchingLocalFiles(private val library: MusicLibrary, artwork: ArtworkLibrary? = null) : LocalSources {
    private val scanner = LocalFiles(library, artwork)
    override suspend fun roots(): List<String> = (scanner.roots() + library.localFolderRules().included).distinct()
    override suspend fun pendingRefreshLocations(): List<String> =
        (scanner.pendingRefreshLocations() + (roots() - scanner.roots().toSet())).distinct()
    override suspend fun scan(location: String, force: Boolean, onProgress: suspend (LocalScanProgress) -> Unit): List<Track> = scanner.scan(location, force, onProgress)
    override suspend fun scanSong(location: String): List<Track> = scanner.scanSong(location)

    /**
     * The folders to scan again: each one that had a music file or a subfolder change, emitted once its changes pause for
     * 300 ms (or after a second of steady writes). A cover, a sidecar or a partial download changes nothing in the library, so it
     * is ignored, and an unrelated change no longer rescans every library folder.
     */
    override val changes: Flow<List<String>> = flow {
        FileSystems.getDefault().newWatchService().use { watcher ->
            val directories = mutableMapOf<Path, WatchKey>()
            // Folders that cannot be watched, such as an unreadable lost+found or any past the inotify watch limit, are skipped
            // and reported once, rather than stopping the watcher or being retried every second.
            val unwatchable = mutableSetOf<Path>()
            fun skip(path: Path, error: IOException) {
                if (unwatchable.add(path)) System.err.println("Not watching $path for music changes: ${error.message}")
            }
            fun registerTree(root: Path): Boolean {
                if (!Files.isDirectory(root)) return false
                var added = false
                // Scans follow linked folders, so the watcher does too.
                Files.walkFileTree(root, setOf(FileVisitOption.FOLLOW_LINKS), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                        // Tag edits stage their files here, so watching it would rescan for every edit.
                        if (directory.fileName?.toString() == ".nevumi-edits") return FileVisitResult.SKIP_SUBTREE
                        if (directory !in directories && directory !in unwatchable) try {
                            directories[directory] = directory.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                            added = true
                        } catch (error: IOException) { skip(directory, error) }
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                        // A file removed while the folder is read is no failure.
                        if (error !is NoSuchFileException) skip(file, error)
                        return FileVisitResult.CONTINUE
                    }
                    // A folder that fails while its entries are listed keeps its watch on what was read, instead of ending the walk.
                    override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                        if (error != null && error !is NoSuchFileException) skip(directory, error)
                        return FileVisitResult.CONTINUE
                    }
                })
                return added
            }
            var watched = emptyList<Path>()
            var started = false
            val pending = LinkedHashSet<Path>()
            var pendingSince = 0L
            suspend fun flush() {
                // A folder inside another pending one is covered by that folder's scan.
                val folders = pending.sortedBy { it.nameCount }.fold(mutableListOf<Path>()) { kept, path ->
                    kept.apply { if (none { path.startsWith(it) }) add(path) }
                }
                pending.clear()
                if (folders.isNotEmpty()) emit(folders.map { it.toString() })
            }
            while (currentCoroutineContext().isActive) {
                val roots = roots().map { Path.of(it) }
                if (roots != watched) {
                    // A folder removed from the library stops being watched instead of rescanning on every change.
                    directories.entries.removeAll { (directory, key) -> roots.none { directory.startsWith(it) }.also { if (it) key.cancel() } }
                    unwatchable.removeAll { path -> roots.none { path.startsWith(it) } }
                    watched = roots
                }
                val added = roots.filter { it !in directories && it !in unwatchable && registerTree(it) }
                // At startup the controller scans what changed while the app was closed; a folder added later may have changed
                // before its watch existed.
                if (started && added.isNotEmpty()) {
                    val changed = scanner.pendingRefreshLocations().toSet()
                    pending.addAll(added.filter { it.toString() in changed })
                }
                started = true
                val now = System.currentTimeMillis()
                if (pending.isNotEmpty() && pendingSince == 0L) pendingSince = now
                val key = watcher.poll(if (pending.isEmpty()) 1_000 else 300, TimeUnit.MILLISECONDS)
                if (key == null || pending.isNotEmpty() && now - pendingSince >= 1_000) {
                    flush(); pendingSince = 0L
                    if (key == null) continue
                }
                val directory = key.watchable() as? Path
                for (event in key.pollEvents()) {
                    if (directory == null) continue
                    // Lost events can hide anything, including new subfolders, so every library folder is walked and scanned.
                    if (event.kind() == OVERFLOW) { roots.forEach { registerTree(it) }; pending.addAll(roots); continue }
                    val name = event.context() as? Path ?: continue
                    val path = directory.resolve(name)
                    val folder = Files.isDirectory(path)
                    if (event.kind() == ENTRY_CREATE && folder) registerTree(path)
                    val music = name.toString().substringAfterLast('.', "").lowercase() in localMusicExtensions
                    if (music || folder || event.kind() == ENTRY_DELETE && path in directories) pending.add(directory)
                }
                // A deleted folder takes its subfolders' watches with it.
                if (!key.reset()) directories.entries.removeAll { (path, watch) ->
                    watch == key || directory != null && path.startsWith(directory) && !Files.isDirectory(path)
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
