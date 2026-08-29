package fr.steren.attractors

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * The launcher screen: the settings, plus a way into the wallpaper picker. A live
 * wallpaper has no real app of its own, this is only how you reach it.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }

        findViewById<Button>(R.id.set_wallpaper).setOnClickListener { openWallpaperPicker() }
    }

    private fun openWallpaperPicker() {
        val component = ComponentName(this, AttractorsWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        try {
            startActivity(intent)
        } catch (error: Exception) {
            // Some devices only offer the list of every live wallpaper.
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (fallbackError: Exception) {
                Toast.makeText(this, R.string.app_name, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
