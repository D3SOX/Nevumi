package me.d3sox.nevumi.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.LowPriority
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RestorePage
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.CopyAll
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class AppIcon(val vector: ImageVector) {
    MINUS(Icons.Rounded.Remove),
    VOLUME(Icons.Rounded.VolumeUp),
    MUTE(Icons.Rounded.VolumeOff),
    CODE(Icons.Rounded.Code),
    LICENSE(Icons.Rounded.Description),
    SEARCH(Icons.Rounded.Search),
    SETTINGS(Icons.Rounded.Settings),
    MENU(Icons.Rounded.Menu),
    SORT(Icons.Rounded.Sort),
    FILTER(Icons.Rounded.FilterList),
    CLOSE(Icons.Rounded.Close),
    BACK(Icons.Rounded.ArrowBack),
    UP(Icons.Rounded.ExpandLess),
    DOWN(Icons.Rounded.ExpandMore),
    FORWARD(Icons.Rounded.ChevronRight),
    PLAY(Icons.Rounded.PlayArrow),
    PAUSE(Icons.Rounded.Pause),
    NEXT(Icons.Rounded.SkipNext),
    PREVIOUS(Icons.Rounded.SkipPrevious),
    QUEUE(Icons.Rounded.QueueMusic),
    SAVE(Icons.Rounded.Save),
    PLAYLIST_MEMBER(Icons.Rounded.LibraryAddCheck),
    PLAYLIST_PLUS(Icons.Rounded.AddCircleOutline),
    PLAYLIST_CHECK(Icons.Rounded.CheckCircle),
    CLEAR_QUEUE(Icons.Rounded.ClearAll),
    DUPLICATES(Icons.Rounded.CopyAll),
    REPEAT(Icons.Rounded.Repeat),
    REPEAT_ONE(Icons.Rounded.RepeatOne),
    REPEAT_COUNT(Icons.Rounded.Numbers),
    SHUFFLE(Icons.Rounded.Shuffle),
    REMOVE(Icons.Rounded.DeleteOutline),
    DRAG(Icons.Rounded.DragIndicator),
    MUSIC(Icons.Rounded.MusicNote),
    FOLDER(Icons.Rounded.Folder),
    CLOUD(Icons.Rounded.Cloud),
    DOWNLOAD(Icons.Rounded.Download),
    AVAILABLE_OFFLINE(Icons.Rounded.CloudDone),
    CHECK(Icons.Rounded.Check),
    PLUS(Icons.Rounded.Add),
    FAVORITE(Icons.Rounded.FavoriteBorder),
    FAVORITE_FILLED(Icons.Rounded.Favorite),
    EDIT(Icons.Rounded.Edit),
    UNDO(Icons.Rounded.Undo),
    REDO(Icons.Rounded.Redo),
    INFO(Icons.Rounded.Info),
    ALBUM(Icons.Rounded.Album),
    EQUALIZER(Icons.Rounded.GraphicEq),
    HEADPHONES(Icons.Rounded.Headphones),
    QR_SCAN(Icons.Rounded.QrCodeScanner),
    HOME(Icons.Rounded.Home),
    WAVEFORM(Icons.Rounded.Waves),
    SWIPE_RIGHT(Icons.Rounded.SwipeRight),
    SWIPE_LEFT(Icons.Rounded.SwipeLeft),
    QUICK_ACTION(Icons.Rounded.Bolt),
    TABS(Icons.Rounded.Tab),
    GESTURES(Icons.Rounded.Gesture),
    STARTUP(Icons.Rounded.RocketLaunch),
    SOURCE_PRIORITY(Icons.Rounded.SwapVert),
    CACHE(Icons.Rounded.Memory),
    BACKUP_FILES(Icons.Rounded.RestorePage),
    SERVER_FILES(Icons.Rounded.FolderShared),
    SERVER(Icons.Rounded.Dns),
    SCROBBLING(Icons.Rounded.CloudSync),
    CONFIRM(Icons.AutoMirrored.Rounded.HelpOutline),
    ARTIST(Icons.Rounded.Person),
    THEME(Icons.Rounded.Palette),
    SYSTEM_THEME(Icons.Rounded.BrightnessAuto),
    LIGHT_THEME(Icons.Rounded.LightMode),
    DARK_THEME(Icons.Rounded.DarkMode),
    SYSTEM_COLORS(Icons.Rounded.Wallpaper),
    ARTWORK_COLORS(Icons.Rounded.Image),
    SPEED(Icons.Rounded.Speed),
    CROSSFADE(Icons.Rounded.CompareArrows),
    PITCH(Icons.Rounded.Tune),
    STORAGE(Icons.Rounded.Storage),
    WIFI(Icons.Rounded.Wifi),
    NETWORK(Icons.Rounded.Public),
    HISTORY(Icons.Rounded.History),
    DEVICES(Icons.Rounded.Devices),
    MORE(Icons.Rounded.MoreVert),
    TITLE(Icons.Rounded.SortByAlpha),
    ALBUM_ARTIST(Icons.Rounded.Groups),
    GENRE(Icons.Rounded.Style),
    RECENT(Icons.Rounded.Schedule),
    YEAR(Icons.Rounded.CalendarMonth),
    RATING(Icons.Rounded.StarOutline),
    RATING_FILLED(Icons.Rounded.Star),
    STATISTICS(Icons.Rounded.BarChart),
    RECOMMENDATIONS(Icons.Rounded.AutoAwesome),
    PLAY_NEXT(QueueIcons.playNext),
    PLAY_AFTER(Icons.Rounded.LowPriority),
    APPEND(QueueIcons.append),
    KEY(Icons.Rounded.VpnKey),
    LOCK(Icons.Rounded.Lock),
    LINK(Icons.Rounded.Link),
    UNLINK(Icons.Rounded.LinkOff),
    UNLOCK(Icons.Rounded.LockOpen),
    REFRESH(Icons.Rounded.Refresh),
    RESET(Icons.Rounded.RestartAlt),
    SYNC(Icons.Rounded.Sync),
    CAMERA(Icons.Rounded.PhotoCamera),
    IMPORT(Icons.Rounded.FileOpen),
    OPEN_EXTERNAL(Icons.AutoMirrored.Rounded.OpenInNew),
    SIGN_IN(Icons.AutoMirrored.Rounded.Login),
    SIGN_OUT(Icons.AutoMirrored.Rounded.Logout),
    TAILSCALE(Icons.Rounded.VpnLock),
    ASCENDING(Icons.Rounded.ArrowUpward),
    DESCENDING(Icons.Rounded.ArrowDownward),
    LIBRARY(Icons.Rounded.LibraryMusic),
    DEVICE(Icons.Rounded.Smartphone),
    GRID(Icons.Rounded.GridView),
    LIST(Icons.Rounded.ViewList),
    MORE_PAGES(Icons.Rounded.MoreHoriz),
    SELECT_ALL(Icons.Rounded.SelectAll),
    SELECT_NONE(Icons.Rounded.Deselect);
}

internal fun me.d3sox.nevumi.queue.QueueAction.icon() = when (this) {
    me.d3sox.nevumi.queue.QueueAction.PLAY_NEXT -> AppIcon.PLAY_NEXT
    me.d3sox.nevumi.queue.QueueAction.PLAY_AFTER -> AppIcon.PLAY_AFTER
    me.d3sox.nevumi.queue.QueueAction.APPEND -> AppIcon.APPEND
}

internal fun me.d3sox.nevumi.library.LibraryTab.icon() = when (this) {
    me.d3sox.nevumi.library.LibraryTab.SEARCH -> AppIcon.SEARCH
    me.d3sox.nevumi.library.LibraryTab.HOME -> AppIcon.HOME
    me.d3sox.nevumi.library.LibraryTab.SONGS -> AppIcon.MUSIC
    me.d3sox.nevumi.library.LibraryTab.ALBUMS -> AppIcon.ALBUM
    me.d3sox.nevumi.library.LibraryTab.ARTISTS -> AppIcon.ARTIST
    me.d3sox.nevumi.library.LibraryTab.ALBUM_ARTISTS -> AppIcon.ALBUM_ARTIST
    me.d3sox.nevumi.library.LibraryTab.GENRES -> AppIcon.GENRE
    me.d3sox.nevumi.library.LibraryTab.FOLDERS -> AppIcon.FOLDER
    me.d3sox.nevumi.library.LibraryTab.FAVORITES -> AppIcon.FAVORITE
    me.d3sox.nevumi.library.LibraryTab.RECENT -> AppIcon.RECENT
    me.d3sox.nevumi.library.LibraryTab.PLAYLISTS -> AppIcon.QUEUE
    me.d3sox.nevumi.library.LibraryTab.HISTORY -> AppIcon.HISTORY
    me.d3sox.nevumi.library.LibraryTab.STATISTICS -> AppIcon.STATISTICS
    me.d3sox.nevumi.library.LibraryTab.RECOMMENDATIONS -> AppIcon.RECOMMENDATIONS
}

internal fun me.d3sox.nevumi.library.LibrarySort.icon() = when (this) {
    me.d3sox.nevumi.library.LibrarySort.TITLE -> AppIcon.TITLE
    me.d3sox.nevumi.library.LibrarySort.ARTIST -> AppIcon.ARTIST
    me.d3sox.nevumi.library.LibrarySort.ALBUM -> AppIcon.ALBUM
    me.d3sox.nevumi.library.LibrarySort.RECENT -> AppIcon.RECENT
    me.d3sox.nevumi.library.LibrarySort.RATING -> AppIcon.RATING
    me.d3sox.nevumi.library.LibrarySort.PLAY_COUNT -> AppIcon.STATISTICS
    me.d3sox.nevumi.library.LibrarySort.LAST_PLAYED -> AppIcon.HISTORY
}

@Composable
internal fun AppIconButton(icon: AppIcon, label: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, modifier, enabled = enabled) { Icon(icon.vector, label) }
}
