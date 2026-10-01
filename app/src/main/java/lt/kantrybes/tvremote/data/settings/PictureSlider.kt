package lt.kantrybes.tvremote.data.settings

internal object PictureSlider {
    const val CONTRAST = "contrast"
    const val BRIGHTNESS = "brightness"
    const val COLOR_SATURATION = "color_saturation"
    const val SHARPNESS = "sharpness"

    val items: List<String> = listOf(BRIGHTNESS, CONTRAST, COLOR_SATURATION, SHARPNESS)

    fun shownByDefault(item: String): Boolean = item == BRIGHTNESS

    fun isItem(item: String): Boolean = item in items
}

internal data class PictureSliderPref(
    val item: String,
    val shown: Boolean,
    val value: Int,
)
