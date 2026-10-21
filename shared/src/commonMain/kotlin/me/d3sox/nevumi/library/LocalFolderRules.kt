package me.d3sox.nevumi.library

data class LocalFolderRules(val included: List<String> = emptyList(), val excluded: List<String> = emptyList()) {
    fun allows(folder: String, alternative: String? = null): Boolean {
        val folders = listOfNotNull(folder, alternative)
        fun contains(rule: String): Boolean = folders.any { covers(rule, it) }
        return excluded.none(::contains) && (included.isEmpty() || included.any(::contains))
    }

    /** An excluded folder that contains [folder]. Exclusions also hide subfolders, so only removing it lets [folder] in. */
    fun excludedParent(folder: String): String? = excluded.firstOrNull { covers(it, folder) && !covers(folder, it) }

    /** These rules changed so [folder] joins the library, unless an [excludedParent] still hides it. */
    fun allowing(folder: String) = LocalFolderRules(
        if (included.isEmpty() || included.any { covers(it, folder) }) included else included + folder,
        excluded.filterNot { covers(folder, it) })

    /** Whether [rule] names [folder] or a folder that contains it. */
    private fun covers(rule: String, folder: String): Boolean =
        normalize(folder).let { it == normalize(rule) || it.startsWith(normalize(rule) + "/") }

    private fun normalize(path: String): String = path.trim().replace('\\', '/').trimEnd('/')
        .removePrefix("/storage/emulated/0/").removePrefix("/sdcard/").removePrefix("./")
}
