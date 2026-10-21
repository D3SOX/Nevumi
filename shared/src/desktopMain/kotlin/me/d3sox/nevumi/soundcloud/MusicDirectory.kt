package me.d3sox.nevumi.soundcloud

import java.io.File

/** The user's music folder from XDG user directories, or ~/Music. */
fun musicDirectory(environment: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): String {
    environment["XDG_MUSIC_DIR"]?.takeIf { it.startsWith("/") }?.let { return it }
    val config = environment["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.config"
    val configured = try { File(config, "user-dirs.dirs").readLines() } catch (_: Exception) { emptyList() }
        .firstOrNull { it.trimStart().startsWith("XDG_MUSIC_DIR=") }
        ?.substringAfter('=')?.trim()?.removeSurrounding("\"")?.replace("\$HOME", home)?.trimEnd('/')
    // A music directory equal to the home directory means the user disabled it.
    return configured?.takeIf { it.startsWith("/") && it != home.trimEnd('/') } ?: "$home/Music"
}
