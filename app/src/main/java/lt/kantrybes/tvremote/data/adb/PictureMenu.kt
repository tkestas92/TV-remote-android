package lt.kantrybes.tvremote.data.adb

internal object PictureMenu {
    const val COMMAND = "am start -n com.tcl.settings/.MainActivity"

    fun started(output: String): Boolean {
        return "Starting:" in output && "Error" !in output
    }
}
