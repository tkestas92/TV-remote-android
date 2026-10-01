package lt.kantrybes.tvremote.data.adb

import lt.kantrybes.tvremote.data.settings.Shortcuts

/**
 * Resolves a TV app's launcher activity once, then starts it with `am start`
 * so a shortcut does not pay for `monkey`.
 */
internal object LauncherActivity {
    const val LEANBACK = "android.intent.category.LEANBACK_LAUNCHER"
    const val LAUNCHER = "android.intent.category.LAUNCHER"

    fun resolveCommand(packageName: String, category: String): String {
        check(Shortcuts.isPackageName(packageName)) { "Unsafe package name" }
        check(category == LEANBACK || category == LAUNCHER) { "Unexpected category" }
        return "cmd package resolve-activity --brief -c $category $packageName"
    }

    fun startCommand(component: String): String {
        check(isComponent(component)) { "Unsafe component" }
        return "am start -n $component"
    }

    /** Last non-empty line, when it is `package/activity` for [packageName]. */
    fun componentFromResolve(output: String, packageName: String): String? {
        val line = output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
        if (!line.startsWith("$packageName/")) return null
        return line.takeIf { isComponent(it) }
    }

    fun startFailed(output: String): Boolean {
        return output.lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed.startsWith("Error:") ||
                trimmed.startsWith("Error type") ||
                "Exception" in trimmed
        }
    }

    fun isComponent(value: String): Boolean {
        val slash = value.indexOf('/')
        if (slash <= 0 || slash != value.lastIndexOf('/')) return false
        val packageName = value.substring(0, slash)
        val className = value.substring(slash + 1)
        if (!Shortcuts.isPackageName(packageName)) return false
        return CLASS_NAME.matches(className)
    }

    private val CLASS_NAME = Regex("""\.?[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_$]*)*""")
}
