package lt.kantrybes.tvremote.data.adb

internal object YouTubeShare {
    private val pattern = Regex(
        """https?://(?:(?:www|m)\.)?(?:youtube\.com/(?:watch\?\S+|shorts/\S+)|youtu\.be/\S+)""",
        RegexOption.IGNORE_CASE,
    )

    fun find(text: String): String? {
        val raw = pattern.find(text)?.value ?: return null
        return raw.trimEnd('.', ',', ';', ')', ']', '>', '"', '\'')
    }

    fun command(url: String): String {
        val quoted = url.replace("'", "'\\''")
        return "am start -a android.intent.action.VIEW -d '$quoted' com.google.android.youtube.tv"
    }

    fun started(output: String): Boolean = "Starting:" in output && "Error" !in output
}
