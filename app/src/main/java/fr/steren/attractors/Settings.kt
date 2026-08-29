package fr.steren.attractors

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** The user's settings, read once and then only re-read when they change. */
data class Settings(
    val paletteKey: String,
    val text: String,
    val nbAttractors: Int,
    val particleDensity: Float,
    /** How long a piece animates for, in milliseconds. 0 means "until it is stopped". */
    val durationMillis: Long,
    /** Milliseconds between two frames reaching the screen. */
    val frameIntervalMillis: Long,
    /**
     * How long a piece is kept before a new one is painted, in milliseconds, checked when
     * the wallpaper becomes visible. 0 means "keep it until asked for a new one", and
     * [REGENERATE_ALWAYS] means "every time the wallpaper is shown".
     */
    val regenerateAfterMillis: Long,
    val tapToRegenerate: Boolean,
    val respectBatterySaver: Boolean,
) {
    /** Builds the configuration of one piece, in the given colors. */
    fun pieceConfig(palette: Palette) = PieceConfig(
        backgroundColor = palette.background,
        color1 = palette.color1,
        color2 = palette.color2,
        nbAttractors = nbAttractors,
        particleDensity = particleDensity,
        text = text,
    )

    /** Whether a piece rendered with [other] would look the same. */
    fun rendersTheSameAs(other: Settings): Boolean =
        paletteKey == other.paletteKey &&
            text == other.text &&
            nbAttractors == other.nbAttractors &&
            particleDensity == other.particleDensity

    companion object {
        const val REGENERATE_ALWAYS = -1L

        const val KEY_PALETTE = "palette"
        const val KEY_TEXT = "text"
        const val KEY_ATTRACTORS = "attractors"
        const val KEY_DENSITY = "density"
        const val KEY_DURATION = "duration"
        const val KEY_FRAMERATE = "framerate"
        const val KEY_REGENERATE = "regenerate"
        const val KEY_TAP = "tap_to_regenerate"
        const val KEY_BATTERY_SAVER = "respect_battery_saver"

        fun read(context: Context): Settings = read(PreferenceManager.getDefaultSharedPreferences(context))

        fun read(prefs: SharedPreferences) = Settings(
            paletteKey = prefs.getString(KEY_PALETTE, Palette.SYSTEM) ?: Palette.SYSTEM,
            text = prefs.getString(KEY_TEXT, "")?.trim().orEmpty(),
            nbAttractors = prefs.getInt(KEY_ATTRACTORS, 25).coerceIn(1, 200),
            particleDensity = (prefs.getString(KEY_DENSITY, "900") ?: "900").toFloatOrNull() ?: 900f,
            durationMillis = 1000L * ((prefs.getString(KEY_DURATION, "45") ?: "45").toLongOrNull() ?: 45L),
            frameIntervalMillis = 1000L / ((prefs.getString(KEY_FRAMERATE, "30") ?: "30").toLongOrNull() ?: 30L),
            regenerateAfterMillis = (prefs.getString(KEY_REGENERATE, "21600") ?: "21600").toLongOrNull()
                ?.let { if (it == REGENERATE_ALWAYS) it else it * 1000L } ?: 21_600_000L,
            tapToRegenerate = prefs.getBoolean(KEY_TAP, true),
            respectBatterySaver = prefs.getBoolean(KEY_BATTERY_SAVER, true),
        )
    }
}
