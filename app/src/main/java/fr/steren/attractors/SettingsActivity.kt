package fr.steren.attractors

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Opened by the wallpaper picker, through `android:settingsActivity`. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The settings are the whole of this screen, so they go straight into the content
        // view the window already has rather than into a layout that holds nothing else.
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }
}
