package me.d3sox.nevumi.settings

import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.LibrarySort
import me.d3sox.nevumi.library.LibraryQuery
import me.d3sox.nevumi.library.LibraryTab
import kotlinx.serialization.Serializable
import me.d3sox.nevumi.playback.AudioEffects

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class ColorMode { SYSTEM, ARTWORK, CUSTOM }

@Serializable
enum class CollectionView { LIST, GRID }

@Serializable
enum class CollectionSort { NAME, ARTIST, YEAR, SONG_COUNT, RECENT, CUSTOM }

@Serializable
data class CollectionLayout(val view: CollectionView = CollectionView.GRID, val columns: Int? = null,
    val sort: CollectionSort = CollectionSort.NAME, val descending: Boolean = false, val order: List<String> = emptyList())

/** How the playlist picker orders playlists; [LIBRARY] follows the Playlists tab's order. */
@Serializable
enum class PlaylistPickerSort { UPDATED, ADDED, NAME, PLAYED, LIBRARY }

@Serializable
data class LibraryPosition(val index: Int = 0, val offset: Int = 0)

@Serializable
enum class HomeSectionId(val label: String) {
    MIXES("Mixes"), RECENT("Recent listens"), TOP("Top listens"), RECENT_ALBUMS("Recent albums"),
    RECENT_ARTISTS("Recent artists"), TOP_ALBUMS("Top albums"), TOP_ARTISTS("Top artists"),
    REDISCOVER("Rediscover"), PLAYLISTS("Your playlists"), SAVED_QUEUES("Saved queues"), ADDED("Recently added")
}

@Serializable
enum class QueueRecoveryMode(val label: String) { BEFORE_REPLACE("Before replacing or clearing"), ON_CHANGE("After queue edits"), TIMER("On a timer") }

@Serializable
data class MusicSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoledBlack: Boolean = false,
    val colors: ColorMode = ColorMode.SYSTEM,
    val customColor: Long = 0xFF6750A4,
    val swipeRight: TrackAction = TrackAction.PLAY_NEXT,
    val swipeLeft: TrackAction = TrackAction.APPEND,
    val quickAction: TrackAction = TrackAction.ADD_TO_PLAYLIST,
    val showMoreButtons: Boolean = false,
    val sourcePriority: List<SourceKind> = listOf(SourceKind.LOCAL, SourceKind.DOWNLOAD, SourceKind.SERVER),
    val cacheBytes: Long = 2L * 1024 * 1024 * 1024,
    val downloadsPaused: Boolean = false,
    val downloadOnMetered: Boolean = false,
    val speed: Double = 1.0,
    val pitch: Double = 1.0,
    val audioEffects: AudioEffects = AudioEffects(),
    val crossfadeDurationMs: Long = 0,
    val gaplessPlayback: Boolean = true,
    val milkDrop: me.d3sox.nevumi.visualizer.MilkDropSettings = me.d3sox.nevumi.visualizer.MilkDropSettings(),
    val showRemainingTime: Boolean = false,
    val showPreviousQueueTracks: Boolean = true,
    val confirmQueueJumps: Boolean = true,
    val confirmNextUpInterruption: Boolean = false,
    val queueRecoveryEnabled: Boolean = false,
    val queueRecoveryMode: QueueRecoveryMode = QueueRecoveryMode.BEFORE_REPLACE,
    val queueRecoveryIntervalMinutes: Int = 5,
    val precomputeWaveforms: Boolean = true,
    val showAudioInfo: Boolean = true,
    val previousQueueTrackLimit: Int = 5,
    val recordListeningHistory: Boolean = true,
    val scrobblePercent: Int = 50,
    val scrobbleMaxSeconds: Int = 240,
    val songSort: LibrarySort = LibrarySort.TITLE,
    val songOrderReversed: Boolean = false,
    val sourceFilter: SourceKind? = null,
    val libraryFilter: LibraryQuery = LibraryQuery(),
    val libraryTabs: List<LibraryTab> = listOf(LibraryTab.HOME) + LibraryTab.entries.filter { it != LibraryTab.HOME && it != LibraryTab.ALBUM_ARTISTS },
    val startupTab: LibraryTab? = null,
    val lastLibraryTab: LibraryTab = LibraryTab.SONGS,
    val homeSections: List<HomeSectionId> = HomeSectionId.entries,
    val disabledHomeSections: Set<HomeSectionId> = emptySet(),
    val rememberScrollPositions: Boolean = true,
    val libraryPositions: Map<String, LibraryPosition> = emptyMap(),
    val collectionLayouts: Map<LibraryTab, CollectionLayout> = emptyMap(),
    val playlistPickerSort: PlaylistPickerSort = PlaylistPickerSort.UPDATED,
    val includeFolders: List<String> = emptyList(),
    val excludeFolders: List<String> = emptyList(),
    val undoRetentionDays: Int = 30,
    val undoBackupBytes: Long = 1_073_741_824L,
) {
    fun collectionLayout(tab: LibraryTab): CollectionLayout = collectionLayouts[tab] ?: CollectionLayout(view = when (tab) {
        LibraryTab.ARTISTS, LibraryTab.ALBUM_ARTISTS, LibraryTab.GENRES, LibraryTab.FOLDERS -> CollectionView.LIST
        else -> CollectionView.GRID
    })
}

class MusicPreferences(private val store: MusicStore) {
    suspend fun load(): MusicSettings = store.read("settings:music") ?: MusicSettings()
    suspend fun update(change: (MusicSettings) -> MusicSettings): MusicSettings = store.transaction {
        val settings = change(load())
        require(settings.customColor in 0xFF000000..0xFFFFFFFF) { "Choose an opaque RGB color" }
        require(settings.cacheBytes >= 0) { "Cache size cannot be negative" }
        require(settings.libraryTabs.isNotEmpty() && settings.libraryTabs.distinct().size == settings.libraryTabs.size) {
            "Choose at least one library tab and include each tab once"
        }
        require(settings.collectionLayouts.values.all { it.columns == null || it.columns in 2..6 }) { "Choose automatic or between 2 and 6 columns" }
        require(settings.homeSections.size == HomeSectionId.entries.size && settings.homeSections.toSet() == HomeSectionId.entries.toSet()) {
            "Include each Home section once"
        }
        require(settings.undoRetentionDays in 1..3650 && settings.undoBackupBytes >= 0) { "Choose a valid undo retention period and size" }
        require(settings.speed in 0.5..2.0) { "Playback speed must be between 0.5 and 2" }
        require(settings.pitch in 0.5..2.0) { "Playback pitch must be between 0.5 and 2" }
        settings.audioEffects.validate()
        require(settings.crossfadeDurationMs in 0..12_000) { "Crossfade must be between 0 and 12 seconds" }
        require(settings.previousQueueTrackLimit in 1..100) { "Choose between 1 and 100 previous tracks" }
        require(settings.queueRecoveryIntervalMinutes in 1..60) { "Choose a recovery interval between 1 and 60 minutes" }
        require(settings.scrobblePercent in 1..100) { "Choose a scrobbling percentage between 1 and 100" }
        require(settings.scrobbleMaxSeconds in 0..600) { "Choose a scrobbling time limit between 0 and 600 seconds" }
        require(settings.sourcePriority.size == SourceKind.entries.size && settings.sourcePriority.toSet() == SourceKind.entries.toSet()) {
            "Choose each audio source once"
        }
        store.put("settings:music", "settings", settings)
        settings
    }
}
