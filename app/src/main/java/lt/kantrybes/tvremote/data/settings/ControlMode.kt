package lt.kantrybes.tvremote.data.settings

internal enum class ControlMode {
    BUTTONS,
    DPAD,
    ;

    fun stored(): String = when (this) {
        BUTTONS -> BUTTONS_VALUE
        DPAD -> DPAD_VALUE
    }

    companion object {
        const val BUTTONS_VALUE = "buttons"
        const val DPAD_VALUE = "dpad"
        const val SWIPE_VALUE = "swipe"

        fun fromStored(value: String?): ControlMode {
            return when (value?.trim()?.lowercase()) {
                BUTTONS_VALUE -> BUTTONS
                else -> DPAD
            }
        }
    }
}
