package fr.steren.attractors

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Opened by the wallpaper picker, through `android:settingsActivity`. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
    }
}
