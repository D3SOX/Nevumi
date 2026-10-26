@file:Suppress("FunctionName")

package me.d3sox.nevumi.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.next_track
import me.d3sox.nevumi.resources.pause
import me.d3sox.nevumi.resources.play
import me.d3sox.nevumi.resources.previous_track
import me.d3sox.nevumi.resources.tray_quit
import me.d3sox.nevumi.resources.tray_show
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.annotations.DBusProperty.Access
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.jetbrains.compose.resources.getString
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** An icon as StatusNotifierItem sends it: ARGB32 pixels in network byte order. */
class TrayPixmap(@field:Position(0) val width: Int, @field:Position(1) val height: Int, @field:Position(2) val data: ByteArray) : Struct()

class TrayToolTip(@field:Position(0) val iconName: String, @field:Position(1) val icon: List<TrayPixmap>,
    @field:Position(2) val title: String, @field:Position(3) val text: String) : Struct()

interface TrayPixmaps : TypeRef<List<TrayPixmap>>
interface TrayToolTipType : TypeRef<TrayToolTip>

@DBusInterfaceName("org.kde.StatusNotifierItem")
@DBusProperty(name = "Category", type = String::class, access = Access.READ)
@DBusProperty(name = "Id", type = String::class, access = Access.READ)
@DBusProperty(name = "Title", type = String::class, access = Access.READ)
@DBusProperty(name = "Status", type = String::class, access = Access.READ)
@DBusProperty(name = "WindowId", type = Int::class, access = Access.READ)
@DBusProperty(name = "IconName", type = String::class, access = Access.READ)
@DBusProperty(name = "IconPixmap", type = TrayPixmaps::class, access = Access.READ)
@DBusProperty(name = "ToolTip", type = TrayToolTipType::class, access = Access.READ)
@DBusProperty(name = "ItemIsMenu", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "Menu", type = DBusPath::class, access = Access.READ)
interface StatusNotifierItem : DBusInterface {
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun ContextMenu(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)
    class NewToolTip(path: String) : DBusSignal(path)
}

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

/** A menu entry with its properties and, as variants, its children. */
class MenuLayout(@field:Position(0) val id: Int, @field:Position(1) val properties: Map<String, Variant<*>>,
    @field:Position(2) val children: List<Variant<*>>) : Struct()

class MenuItemProperties(@field:Position(0) val id: Int, @field:Position(1) val properties: Map<String, Variant<*>>) : Struct()

class MenuEvent(@field:Position(0) val id: Int, @field:Position(1) val eventId: String, @field:Position(2) val data: Variant<*>,
    @field:Position(3) val timestamp: UInt32) : Struct()

/** Two return values; D-Bus reads their types from the type arguments. */
class MenuReply<A, B>(@field:Position(0) val first: A, @field:Position(1) val second: B) : Tuple()

interface MenuIconPaths : TypeRef<List<String>>

@DBusInterfaceName("com.canonical.dbusmenu")
@DBusProperty(name = "Version", type = UInt32::class, access = Access.READ)
@DBusProperty(name = "TextDirection", type = String::class, access = Access.READ)
@DBusProperty(name = "Status", type = String::class, access = Access.READ)
@DBusProperty(name = "IconThemePath", type = MenuIconPaths::class, access = Access.READ)
interface DBusMenu : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): MenuReply<UInt32, MenuLayout>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: List<MenuEvent>): List<Int>
    fun AboutToShow(id: Int): Boolean
    fun AboutToShowGroup(ids: List<Int>): MenuReply<List<Int>, List<Int>>
    class LayoutUpdated(path: String, val revision: UInt32, val parent: Int) : DBusSignal(path, revision, parent)
}

/**
 * A system tray icon through StatusNotifierItem, which KDE Plasma shows natively and GNOME with the AppIndicator extension.
 * Clicking it shows the window; its menu controls playback, shows the window and quits. [address] overrides the session bus in tests.
 */
class LinuxTrayIcon(private val controller: MusicController, icon: BufferedImage?, private val show: () -> Unit,
    private val quit: () -> Unit, address: String? = null) : StatusNotifierItem, Properties, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connection = (address?.let(DBusConnectionBuilder::forAddress)
        ?: DBusConnectionBuilder.forSessionBus()).withShared(false).build()
    private val busName = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
    private val pixmaps = icon?.let { image -> listOf(22, 44, 64).map { pixmap(image, it) } }.orEmpty()
    private val labels = runBlocking {
        mapOf(PLAY to getString(Res.string.play),
            PAUSE to getString(Res.string.pause),
            PREVIOUS to getString(Res.string.previous_track),
            NEXT to getString(Res.string.next_track),
            SHOW to getString(Res.string.tray_show),
            QUIT to getString(Res.string.tray_quit))
    }
    @Volatile private var revision = 1L
    private val menu = Menu()

    init {
        try {
            connection.exportObject(ITEM_PATH, this)
            connection.exportObject(MENU_PATH, menu)
            connection.requestBusName(busName)
            // The tray host may restart, as Plasma's panel does on updates; a new watcher needs to learn about the icon again.
            connection.addSigHandler(DBus.NameOwnerChanged::class.java) { change ->
                if (change.name == WATCHER && change.newOwner.isNotEmpty()) scope.launch { runCatching { register() } }
            }
            register()
        } catch (error: Exception) { scope.cancel(); connection.close(); throw error }
        scope.launch {
            controller.state.map(::menuState).distinctUntilChanged().drop(1).collect {
                revision++
                connection.sendMessage(DBusMenu.LayoutUpdated(MENU_PATH, UInt32(revision), 0))
            }
        }
        scope.launch {
            controller.state.map(::toolTipText).distinctUntilChanged().drop(1).collect {
                connection.sendMessage(StatusNotifierItem.NewToolTip(ITEM_PATH))
            }
        }
    }

    private fun register() {
        connection.getRemoteObject(WATCHER, "/StatusNotifierWatcher", StatusNotifierWatcher::class.java)
            .RegisterStatusNotifierItem(busName)
    }

    override fun getObjectPath() = ITEM_PATH
    override fun Activate(x: Int, y: Int) = show()
    override fun SecondaryActivate(x: Int, y: Int) { controller.togglePlayback() }
    override fun ContextMenu(x: Int, y: Int) = Unit
    override fun Scroll(delta: Int, orientation: String) = Unit

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> {
        if (interfaceName != "org.kde.StatusNotifierItem") throw DBusExecutionException("Unknown interface")
        return mapOf(
            "Category" to Variant("ApplicationStatus"),
            "Id" to Variant("nevumi"),
            "Title" to Variant("Nevumi"),
            "Status" to Variant("Active"),
            "WindowId" to Variant(0),
            "IconName" to Variant(if (pixmaps.isEmpty()) "me.d3sox.nevumi" else ""),
            "IconPixmap" to Variant(pixmaps, "a(iiay)"),
            "ToolTip" to Variant(TrayToolTip("", emptyList(), "Nevumi", toolTipText(controller.state.value)), "(sa(iiay)ss)"),
            "ItemIsMenu" to Variant(false),
            "Menu" to Variant(DBusPath(MENU_PATH)),
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A =
        (GetAll(interfaceName)[propertyName] ?: throw DBusExecutionException("Unknown property")) as A

    override fun <A> Set(interfaceName: String, propertyName: String, value: A) = throw DBusExecutionException("Property is read only")

    override fun close() { scope.cancel(); connection.close() }

    /** What the menu shows; a change makes tray hosts load it again. */
    private fun menuState(state: AppState) = listOf(state.playback.playing, state.playback.trackId != null,
        state.queue.currentIndex > 0 || (state.queue.currentIndex >= 0 && state.queue.repeatMode == RepeatMode.ALL),
        state.queue.currentIndex >= 0 && (state.queue.currentIndex < state.queue.entries.lastIndex || state.queue.repeatMode == RepeatMode.ALL))

    private fun toolTipText(state: AppState): String {
        val track = state.tracks.find { it.id == state.playback.trackId } ?: return ""
        return listOf(track.metadata.title, track.metadata.artist).filter { it.isNotBlank() }.joinToString(" – ")
    }

    private inner class Menu : DBusMenu, Properties {
        override fun getObjectPath() = MENU_PATH

        private fun items(): List<MenuItemProperties> {
            val (playing, hasTrack, canGoPrevious, canGoNext) = menuState(controller.state.value)
            fun item(id: Int, icon: String, enabled: Boolean = true) = MenuItemProperties(id, mapOf("label" to Variant(labels.getValue(id)),
                "icon-name" to Variant(icon), "enabled" to Variant(enabled)))
            return listOf(
                if (playing) item(PAUSE, "media-playback-pause", hasTrack) else item(PLAY, "media-playback-start", hasTrack),
                item(PREVIOUS, "media-skip-backward", canGoPrevious),
                item(NEXT, "media-skip-forward", canGoNext),
                MenuItemProperties(SEPARATOR, mapOf("type" to Variant("separator"))),
                item(SHOW, "window"),
                item(QUIT, "application-exit"),
            )
        }

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): MenuReply<UInt32, MenuLayout> {
            val items = items()
            val layout = if (parentId == ROOT) MenuLayout(ROOT, mapOf("children-display" to Variant("submenu")),
                if (recursionDepth == 0) emptyList() else items.map { Variant(MenuLayout(it.id, it.properties, emptyList()), LAYOUT_SIGNATURE) })
            else items.firstOrNull { it.id == parentId }?.let { MenuLayout(it.id, it.properties, emptyList()) }
                ?: throw DBusExecutionException("Unknown menu item")
            return MenuReply(UInt32(revision), layout)
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties> =
            items().filter { ids.isEmpty() || it.id in ids }.map { item ->
                MenuItemProperties(item.id, item.properties.filterKeys { propertyNames.isEmpty() || it in propertyNames })
            }

        override fun GetProperty(id: Int, name: String): Variant<*> =
            items().firstOrNull { it.id == id }?.properties?.get(name) ?: throw DBusExecutionException("Unknown menu property")

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            when (id) {
                PLAY, PAUSE -> controller.togglePlayback()
                PREVIOUS -> controller.skip(-1)
                NEXT -> controller.skip(1)
                SHOW -> show()
                QUIT -> quit()
            }
        }

        override fun EventGroup(events: List<MenuEvent>): List<Int> {
            val ids = items().map { it.id }.toSet()
            events.filter { it.id in ids }.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
            return events.map { it.id }.filter { it !in ids }
        }

        override fun AboutToShow(id: Int) = false
        override fun AboutToShowGroup(ids: List<Int>) = MenuReply(emptyList<Int>(), emptyList<Int>())

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> {
            if (interfaceName != "com.canonical.dbusmenu") throw DBusExecutionException("Unknown interface")
            return mapOf("Version" to Variant(UInt32(3)), "TextDirection" to Variant("ltr"), "Status" to Variant("normal"),
                "IconThemePath" to Variant(emptyList<String>(), "as"))
        }

        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(interfaceName: String, propertyName: String): A =
            (GetAll(interfaceName)[propertyName] ?: throw DBusExecutionException("Unknown property")) as A

        override fun <A> Set(interfaceName: String, propertyName: String, value: A) = throw DBusExecutionException("Property is read only")
    }

    private companion object {
        const val ITEM_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/MenuBar"
        const val WATCHER = "org.kde.StatusNotifierWatcher"
        const val LAYOUT_SIGNATURE = "(ia{sv}av)"
        const val ROOT = 0
        const val PLAY = 1
        const val PAUSE = 2
        const val PREVIOUS = 3
        const val NEXT = 4
        const val SEPARATOR = 5
        const val SHOW = 6
        const val QUIT = 7

        fun pixmap(image: BufferedImage, size: Int): TrayPixmap {
            val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            scaled.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(image, 0, 0, size, size, null)
                dispose()
            }
            val data = java.nio.ByteBuffer.allocate(size * size * 4)
            scaled.getRGB(0, 0, size, size, null, 0, size).forEach(data::putInt)
            return TrayPixmap(size, size, data.array())
        }
    }
}
