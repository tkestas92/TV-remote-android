package lt.kantrybes.tvremote.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureSliderTest {
    @Test
    fun onlyBrightnessIsShownByDefault() {
        assertEquals(
            listOf(
                PictureSlider.BRIGHTNESS,
                PictureSlider.CONTRAST,
                PictureSlider.COLOR_SATURATION,
                PictureSlider.SHARPNESS,
            ),
            PictureSlider.items,
        )
        assertTrue(PictureSlider.shownByDefault(PictureSlider.BRIGHTNESS))
        assertFalse(PictureSlider.shownByDefault(PictureSlider.CONTRAST))
        assertFalse(PictureSlider.shownByDefault(PictureSlider.COLOR_SATURATION))
        assertFalse(PictureSlider.shownByDefault(PictureSlider.SHARPNESS))
    }
}
