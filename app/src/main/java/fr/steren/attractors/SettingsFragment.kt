package fr.steren.attractors

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat

/** The settings of the wallpaper, shown both from the picker and from the launcher icon. */
class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }
}
