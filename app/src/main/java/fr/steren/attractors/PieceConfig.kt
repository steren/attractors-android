package fr.steren.attractors

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import org.json.JSONException
import org.json.JSONObject
import kotlin.random.Random

/**
 * Everything that describes one piece. The names and the defaults come from
 * `DEFAULT_CONFIG` in the original attractors.js, so that a piece rendered here looks
 * like the one the web page renders with the same settings.
 */
data class PieceConfig(
    val backgroundColor: Int = Color.parseColor("#57A3BD"),
    val color1: Int = Color.parseColor("#DBCEC1"),
    val color2: Int = Color.parseColor("#F7F6F5"),
    /**
     * Opacity of a single shadow. A single one is far too faint to see: the piece is what
     * they add up to over the frames. It also sets how dark a shadowed area ends up, at
     * `0.5 / shadowOpacity` per channel — see the shadow paint of `AttractorPiece`.
     */
    val shadowOpacity: Float = 0.0034f,
    val shadowScale: Float = 1f,
    val nbAttractors: Int = 25,
    /** Number of particles for a square of 1000 * 1000 screen pixels. */
    val particleDensity: Float = 900f,
    val lineWidth: Float = 0.35f,
    /** Scale at which particles are initialized, 1 being the size of the screen. */
    val initScale: Float = 1f,
    val speed: Float = 1f,
    val text: String = "",
    val textPositionX: Float = 50f,
    val textPositionY: Float = 33f,
    /** Letters are `1 / textWidthRatio` of the width of the piece tall. */
    val textWidthRatio: Float = 12f,
)

/** A named set of colors. */
data class Palette(
    val key: String,
    val background: Int,
    val color1: Int,
    val color2: Int,
    /**
     * Whether these colors were read from the system's accent. Colors that were must not be
     * handed back to the system, which would close a loop; colors that were not, can be.
     */
    val fromSystemAccent: Boolean = false,
) {

    /** The three colors added up: enough to tell one palette of a given key from another. */
    fun colorSum(): Int = background + color1 + color2

    companion object {
        /** The cream of the web palette, at a tone that does not glare against black. */
        private val DARK_TRAIL = Color.parseColor("#B3A99D")

        /** Follows the system theme and the colors the system derives from the user's wallpaper. */
        const val SYSTEM = "system"
        const val RANDOM = "random"

        private fun of(key: String, background: String, color1: String, color2: String) =
            Palette(key, Color.parseColor(background), Color.parseColor(color1), Color.parseColor(color2))

        /** The colors of attractors.steren.fr, and a few variations on them. */
        val ALL = listOf(
            of("original", "#57A3BD", "#DBCEC1", "#F7F6F5"),
            of("ink", "#0B0E12", "#3F5A6B", "#8FA8B8"),
            of("sand", "#C9A227", "#F1E3C3", "#FBF6EA"),
            of("rose", "#7D5A5A", "#E5C1C5", "#F5E6E8"),
            of("forest", "#2E4034", "#A3B18A", "#DAD7CD"),
        )

        fun byKey(key: String): Palette? = ALL.firstOrNull { it.key == key }

        /**
         * The colors to follow the system with, which depends on which way the colors are
         * flowing on this device.
         *
         * Android derives its accent either from a color the user picked outright, or from
         * the wallpaper. When it is picked, the wallpaper follows it. When it comes from
         * the wallpaper, the wallpaper cannot follow it — it would be following itself — so
         * it brings its own colors instead, the ones of attractors.steren.fr, and hands
         * them to the system for the accent to be derived from. Either way one side leads
         * and the other follows, and the piece always has a color to paint with.
         *
         * In the light theme the piece is painted on the accent itself, in the tone the
         * original piece happens to sit at, with two much paler tones of it for the trails.
         *
         * In the dark theme the background is plain black, so the trails are the only thing
         * an OLED panel has to light up. They are mid tones of the accent rather than the
         * pale ones: what makes the original piece easy to live behind is that its trails
         * are only a little lighter than what they are painted on, and against black,
         * near-white trails are anything but.
         */
        fun system(context: Context): Palette {
            val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

            // Before Android 12 there is no system accent to read at all.
            val followTheAccent =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && accentIsChosenByHand(context)

            if (!followTheAccent) {
                val web = ALL.first()
                if (!night) return web.copy(key = SYSTEM)
                // On black, the trails of the web palette are near-white and glare. Its own
                // blue, and its cream brought down to meet it, keep the piece recognisable
                // while staying as easy to live behind as it is in the light theme.
                return Palette(SYSTEM, Color.BLACK, web.background, DARK_TRAIL)
            }

            return if (night) {
                Palette(
                    key = SYSTEM,
                    background = Color.BLACK,
                    color1 = context.getColor(android.R.color.system_accent1_400),
                    color2 = context.getColor(android.R.color.system_accent2_300),
                    fromSystemAccent = true,
                )
            } else {
                Palette(
                    key = SYSTEM,
                    // Tone 60, which is where the background of the original piece sits.
                    background = context.getColor(android.R.color.system_accent1_400),
                    color1 = context.getColor(android.R.color.system_accent2_100),
                    color2 = context.getColor(android.R.color.system_accent1_50),
                    fromSystemAccent = true,
                )
            }
        }

        /**
         * Whether the system's accent is a color the user picked, rather than one taken
         * from the wallpaper.
         *
         * There is no API that answers this, only the setting the theme picker writes. A
         * device that does not have it, or writes something else into it, is treated as
         * taking its colors from the wallpaper — which is the default, and the reading
         * under which the piece brings its own colors rather than following something that
         * may not be there.
         */
        private fun accentIsChosenByHand(context: Context): Boolean {
            val setting = Settings.Secure.getString(
                context.contentResolver,
                THEME_CUSTOMIZATION_SETTING,
            ) ?: return false
            return try {
                JSONObject(setting).optString(COLOR_SOURCE) == COLOR_SOURCE_PRESET
            } catch (error: JSONException) {
                false
            }
        }

        /** `Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES`, which is not public API. */
        private const val THEME_CUSTOMIZATION_SETTING = "theme_customization_overlay_packages"
        private const val COLOR_SOURCE = "android.theme.customization.color_source"
        /** The value the theme picker writes when the accent is a color the user chose. */
        private const val COLOR_SOURCE_PRESET = "preset"

        /**
         * The one palette that [key] stands for, or null for [RANDOM], which stands for a
         * different palette every time and so has no one answer.
         */
        fun fixed(key: String, context: Context): Palette? = when (key) {
            RANDOM -> null
            SYSTEM -> system(context)
            else -> byKey(key) ?: system(context)
        }

        /** The palette to paint the next piece in, for the user's choice of [key]. */
        fun resolve(key: String, context: Context, random: Random): Palette =
            fixed(key, context) ?: random(random)

        /**
         * A palette built around one random hue: a mid-lightness background, and two
         * trails that are lighter than it, the way the original palette is built.
         */
        fun random(random: Random): Palette {
            val hue = random.nextFloat() * 360f
            val background = Color.HSVToColor(
                floatArrayOf(hue, 0.25f + random.nextFloat() * 0.45f, 0.35f + random.nextFloat() * 0.45f)
            )
            // Trails sit next to the background on the color wheel, much lighter and paler.
            val trailHue = (hue + (random.nextFloat() * 80f - 40f) + 360f) % 360f
            val color1 = Color.HSVToColor(floatArrayOf(trailHue, 0.18f, 0.88f))
            val color2 = Color.HSVToColor(floatArrayOf(trailHue, 0.04f, 0.98f))
            return Palette("random", background, color1, color2)
        }
    }
}
