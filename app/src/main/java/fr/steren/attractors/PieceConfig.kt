package fr.steren.attractors

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
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
data class Palette(val key: String, val background: Int, val color1: Int, val color2: Int) {

    /** The three colors added up: enough to tell one palette of a given key from another. */
    fun colorSum(): Int = background + color1 + color2

    companion object {
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
         * The system's own colors: the accent Android derives from the user's home screen,
         * in the tones that suit the theme the device is in.
         *
         * In the light theme the piece is painted on the accent itself, in the tone the
         * original piece happens to sit at, with two much paler tones of it for the trails.
         *
         * In the dark theme the background is plain black, so the trails are the only thing
         * an OLED panel has to light up. They are mid tones of the accent rather than the
         * pale ones: what makes the original piece easy to live behind is that its trails
         * are only a little lighter than what they are painted on, and against black,
         * near-white trails are anything but.
         *
         * Before Android 12 there is no system accent to read, so this falls back to the
         * colors of attractors.steren.fr.
         */
        fun system(context: Context): Palette {
            val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                val original = ALL.first()
                return if (night) {
                    Palette(SYSTEM, Color.BLACK, original.color1, original.color2)
                } else {
                    original.copy(key = SYSTEM)
                }
            }

            return if (night) {
                Palette(
                    key = SYSTEM,
                    background = Color.BLACK,
                    color1 = context.getColor(android.R.color.system_accent1_400),
                    color2 = context.getColor(android.R.color.system_accent2_300),
                )
            } else {
                Palette(
                    key = SYSTEM,
                    // Tone 60, which is where the background of the original piece sits.
                    background = context.getColor(android.R.color.system_accent1_400),
                    color1 = context.getColor(android.R.color.system_accent2_100),
                    color2 = context.getColor(android.R.color.system_accent1_50),
                )
            }
        }

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
