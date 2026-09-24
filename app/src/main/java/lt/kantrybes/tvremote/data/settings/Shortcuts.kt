package lt.kantrybes.tvremote.data.settings

internal data class AppShortcut(
    val label: String,
    val packageName: String,
)

internal object Shortcuts {
    val defaults: List<AppShortcut> = listOf(
        AppShortcut("YouTube", "com.google.android.youtube.tv"),
        AppShortcut("Go3", "lt.go3.android.tv"),
        AppShortcut("Stremio", "com.stremio.one"),
        AppShortcut("Netflix", "com.netflix.ninja"),
    )

    private val packageName = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$""")

    fun isPackageName(value: String): Boolean = packageName.matches(value)

    fun isValid(shortcut: AppShortcut): Boolean {
        return shortcut.label.isNotBlank() && isPackageName(shortcut.packageName)
    }

    fun monkeyCommand(packageName: String): String {
        check(isPackageName(packageName)) { "Unsafe package name" }
        return "monkey -p $packageName -c android.intent.category.LEANBACK_LAUNCHER 1"
    }

    fun encode(shortcuts: List<AppShortcut>): String {
        return shortcuts.joinToString("\n") { shortcut ->
            "${shortcut.label.replace('\n', ' ').replace('\t', ' ')}\t${shortcut.packageName}"
        }
    }

    fun decode(stored: String?): List<AppShortcut> {
        if (stored.isNullOrBlank()) return defaults
        val parsed = stored.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.lastIndex) return@mapNotNull null
            val shortcut = AppShortcut(
                label = line.substring(0, tab).trim(),
                packageName = line.substring(tab + 1).trim(),
            )
            shortcut.takeIf { isValid(it) }
        }.toList()
        return parsed.ifEmpty { defaults }
    }
}
