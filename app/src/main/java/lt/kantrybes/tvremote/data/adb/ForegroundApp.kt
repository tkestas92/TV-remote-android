package lt.kantrybes.tvremote.data.adb

internal object ForegroundApp {
    fun isPackageResumed(dumpsys: String, packageName: String): Boolean {
        return dumpsys.lineSequence().any { line ->
            val focus = "mResumedActivity" in line ||
                "topResumedActivity" in line ||
                "mFocusedApp" in line
            focus && packageName in line
        }
    }
}
