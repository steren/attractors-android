package fr.steren.attractors

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The live wallpaper: paints an attractor piece, then gets out of the way.
 *
 * The whole design is about doing nothing most of the time:
 *
 *  - A piece converges. Once it has been painted for the configured duration, the render
 *    loop is torn down and the wallpaper costs exactly zero CPU until something asks for a
 *    new piece. That is the state the wallpaper is in almost all of its life.
 *  - Nothing is painted while the wallpaper cannot be seen. Being hidden behind an app
 *    stops the loop, and so does the screen going off — including on a phone that leaves
 *    the wallpaper visible while it dozes, which visibility alone would not catch. The
 *    piece is frozen where it stands, still shown, and picks up from there.
 *  - A finished piece is kept on disk, so that coming back from a reboot or from the
 *    system reclaiming the service costs one file read instead of a repaint.
 *  - There is no alarm, no job and no wake lock anywhere: whether a new piece is due is
 *    decided when the wallpaper is picked up again, which is the only moment it could
 *    matter. The one broadcast receiver only listens for the screen going on and off.
 *  - A frame allocates nothing, so painting never wakes the garbage collector.
 */
class AttractorsWallpaperService : WallpaperService() {

    private lateinit var renderThread: HandlerThread
    private lateinit var renderHandler: Handler

    /** Loaded once for the whole service: parsing a font per engine would be wasteful. */
    private val typeface: Typeface by lazy {
        Typeface.createFromAsset(assets, FONT_ASSET)
    }

    /** The engines currently alive, so that a theme change can reach every one of them. */
    private val engines = CopyOnWriteArrayList<AttractorsEngine>()

    /**
     * Whether the screen is really on, rather than off or dozing on an always-on display.
     *
     * Being told the wallpaper is visible is not quite enough to know it can be seen: a
     * phone that keeps its wallpaper visible while it dozes would have a piece paint
     * itself to a black screen, and be finished by the time the screen came back. So a
     * piece waits for this as well. It is frozen where it stands, still shown, and picks
     * up from there.
     */
    @Volatile private var screenOn = true

    private val powerManager: PowerManager? by lazy {
        getSystemService(PowerManager::class.java)
    }

    /**
     * The screen going on or off. The wallpaper has no other way of hearing about it: a
     * phone that keeps the wallpaper visible while it dozes simply says nothing. This is
     * the only receiver the wallpaper registers; it fires a handful of times a day, and
     * does nothing but read one flag.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val was = screenOn
            if (refreshScreenOn() == was) return
            for (engine in engines) engine.onScreenOnChanged(screenOn)
        }
    }

    /** Reads whether the screen is really on, and remembers it for the frames. */
    private fun refreshScreenOn(): Boolean {
        screenOn = powerManager?.isInteractive != false
        return screenOn
    }

    override fun onCreate() {
        super.onCreate()
        renderThread = HandlerThread("attractors-render").apply { start() }
        renderHandler = Handler(renderThread.looper)
        refreshScreenOn()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        renderThread.quitSafely()
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine = AttractorsEngine()

    /**
     * Switching the device between its light and dark theme, or landing on a new set of
     * system colors, arrives here. A piece painted in the colors of the theme the device
     * has just left would be wrong, so it is repainted — and only then.
     *
     * This is the quick way, and it is the one that repaints a piece while it is being
     * looked at. It is not the only way: it reaches nothing if the service is not running
     * when the theme flips, so the colors are checked again whenever the wallpaper becomes
     * visible.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        for (engine in engines) engine.onColorsMayHaveChanged()
    }

    inner class AttractorsEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val prefs: SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(this@AttractorsWallpaperService)
        @Volatile private var settings = Settings.read(prefs)

        /** The piece being painted, or null once it is finished or was restored from disk. */
        private var piece: AttractorPiece? = null
        /** What the surface shows: the piece being painted, or a finished one. */
        private var image: Bitmap? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * The colors handed to the system, so that it can theme itself to match the piece,
         * or null when it must not be told.
         */
        @Volatile private var publishedColors: WallpaperColors? = null

        private var surfaceWidth = 0
        private var surfaceHeight = 0

        /** Where the home screen is scrolled to, from 0 at the far left to 1 at the far right. */
        @Volatile private var xOffset = 0.5f
        @Volatile private var visible = false
        /** Whether a frame is scheduled on the render thread. */
        @Volatile private var animating = false

        /** The colors the piece on screen was painted in. */
        @Volatile private var pieceColors: Palette? = null

        /** `SystemClock.elapsedRealtime` when the piece on screen was started. */
        private var startedAtRealtime = 0L
        private var lastFrameUptime = 0L
        private var nextFrameUptime = 0L
        private var lastRegenerateUptime = 0L
        /** Set once a finished piece has been written to disk, so it is written only once. */
        private var savedToDisk = false
        /**
         * Where the next piece is to carry the attractor of a double tap, in the piece's
         * own pixels, or NaN when it carries none.
         */
        private var pendingTapX = Float.NaN
        private var pendingTapY = 0f
        /** Set to false if the device ever refuses a hardware canvas, to stop trying. */
        @Volatile private var hardwareCanvasWorks = true

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // A piece is painted wider than the screen and slides across it as the home
            // screen pages, so the offsets are wanted after all.
            setOffsetNotificationsEnabled(true)
            setTouchEventsEnabled(settings.tapToRegenerate)
            prefs.registerOnSharedPreferenceChangeListener(this)
            engines.add(this)
        }

        override fun onDestroy() {
            engines.remove(this)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            stopAnimating()
            renderHandler.post { releasePiece() }
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (width == surfaceWidth && height == surfaceHeight) {
                renderHandler.post { blit() }
                return
            }
            surfaceWidth = width
            surfaceHeight = height
            renderHandler.post {
                releasePiece()
                if (!startFromDisk()) startNewPiece()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopAnimating()
            super.onSurfaceDestroyed(holder)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            renderHandler.post { blit() }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            if (xOffset == this.xOffset) return
            this.xOffset = xOffset
            // Only what is on screen has moved, so there is nothing to paint, only to show.
            // While the piece is being painted the next frame is about to do that anyway.
            if (visible && !animating) renderHandler.post { blit() }
        }

        @RequiresApi(Build.VERSION_CODES.O_MR1)
        override fun onComputeColors(): WallpaperColors? = publishedColors

        /**
         * Works out the colors to hand to the system, from the piece as it actually stands.
         *
         * Never when the piece was painted in colors read from the system's own accent:
         * giving those back would close a loop, with the accent derived from a piece that
         * was painted from the accent. Every other piece is painted in colors that came
         * from somewhere else, so nothing it publishes can feed back into it.
         *
         * Runs on the render thread, which is the only one allowed to touch the bitmap.
         */
        private fun publishColors() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return

            val bitmap = image
            val colors = if (
                pieceColors?.fromSystemAccent == true || bitmap == null || bitmap.isRecycled
            ) {
                null
            } else {
                WallpaperColors.fromBitmap(bitmap)
            }

            if (colors == publishedColors) return
            publishedColors = colors
            mainHandler.post { notifyColorsChanged() }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                // Read fresh rather than trusting what the receiver last saw: the service
                // may have just started, with the screen off, having heard nothing yet.
                val isScreenOn = refreshScreenOn()
                renderHandler.post { if (isScreenOn) resume() else blit() }
            } else {
                stopAnimating()
                renderHandler.post { saveToDiskIfFinished() }
            }
        }

        /**
         * The screen going off, or coming back. Going off freezes the piece where it
         * stands; coming back picks it up, if the wallpaper is on screen to be seen.
         */
        fun onScreenOnChanged(isScreenOn: Boolean) {
            if (!isScreenOn) stopAnimating()
            else renderHandler.post { if (visible) resume() }
        }

        /**
         * Picks the wallpaper up: paints a new piece if one is due, and carries on with
         * the one in hand otherwise. This is the one way back to painting, reached from
         * whichever of becoming visible and the screen coming back comes last.
         *
         * Runs on the render thread.
         */
        private fun resume() {
            // The colors are checked here as well as when the configuration changes,
            // because that callback is not something to rely on: it only reaches a
            // wallpaper whose service happens to be alive at the moment the theme flips,
            // which — for a wallpaper that spends nearly all of its life doing nothing —
            // is not a given. This is the last moment before the piece could be seen in
            // the wrong colors, so whatever went missing is caught by the time it matters.
            if (isNewPieceDue() || colorsHaveMoved()) {
                startNewPiece()
            } else {
                // Show the piece as it stands before the loop gets to its first frame,
                // which is a frame interval away.
                blit()
                startAnimating()
            }
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            val updated = Settings.read(this.prefs)
            val looksDifferent = !updated.rendersTheSameAs(settings)
            settings = updated
            setTouchEventsEnabled(updated.tapToRegenerate)
            renderHandler.post {
                if (looksDifferent) startNewPiece() else if (animating) scheduleFrame()
            }
        }

        /*
         * Taps reach a live wallpaper two different ways, and which of them a device uses
         * depends on its launcher: as touch events, when the launcher passes them through,
         * and as the "tap" command, which a launcher sends when it handles a tap on an
         * empty spot itself. Both are listened for, and a tap that arrives twice only
         * counts once.
         *
         * It takes two taps for anything to happen, so that brushing the home screen does
         * not throw a piece away.
         */

        private var lastTouchUptime = 0L
        private var lastCommandTapUptime = 0L

        private val tapDetector: GestureDetector by lazy {
            GestureDetector(
                this@AttractorsWallpaperService,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(event: MotionEvent) = true
                    override fun onDoubleTap(event: MotionEvent): Boolean {
                        onDoubleTapAt(event.x, event.y)
                        return true
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (!settings.tapToRegenerate) return
            lastTouchUptime = SystemClock.uptimeMillis()
            tapDetector.onTouchEvent(event)
        }

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean,
        ): Bundle? {
            if (action != WallpaperManager.COMMAND_TAP || !settings.tapToRegenerate) return null

            val now = SystemClock.uptimeMillis()
            // The touch events already reported this very tap, and were timed properly.
            if (now - lastTouchUptime < TOUCH_ECHO_MILLIS) return null

            if (now - lastCommandTapUptime <= DOUBLE_TAP_MILLIS) {
                onDoubleTapAt(x.toFloat(), y.toFloat())
            }
            lastCommandTapUptime = now
            return null
        }

        /**
         * A double tap, wherever it came from, and unless the other channel just reported
         * this very one: a new piece, carrying a large attractor where the finger landed
         * when that is asked for.
         */
        private fun onDoubleTapAt(x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            if (now - lastRegenerateUptime < DOUBLE_TAP_MILLIS) return
            lastRegenerateUptime = now
            renderHandler.post { startNewPieceFromTap(x, y) }
        }

        // ---------------------------------------------------------------- render thread

        /** Repaints if the piece on screen is no longer in the colors the settings call for. */
        fun onColorsMayHaveChanged() {
            // Not with the screen off: painting a new piece is the work this is all
            // trying to avoid, and the piece would be thrown away for a bare background
            // nobody can see. The colors are checked again when the screen comes back.
            renderHandler.post { if (screenOn && colorsHaveMoved()) startNewPiece() }
        }

        /**
         * Whether the piece on screen is painted in colors the settings no longer call for.
         *
         * The random palette is meant to differ from one piece to the next, so it is left
         * alone; every other choice is expected to track whatever it is following, and the
         * system one follows the theme the device is in.
         */
        private fun colorsHaveMoved(): Boolean {
            val wanted = Palette.fixed(settings.paletteKey, this@AttractorsWallpaperService)
            return wanted != null && wanted != pieceColors
        }

        /** Whether the piece on screen has been up for longer than the user asked. */
        private fun isNewPieceDue(): Boolean {
            if (isPreview) return false
            val after = settings.regenerateAfterMillis
            if (after == 0L) return false
            if (after == Settings.REGENERATE_ALWAYS) return piece == null
            return SystemClock.elapsedRealtime() - startedAtRealtime >= after
        }

        /**
         * Width to paint a piece at: wider than the screen, by the room it needs to slide
         * across as the home screen pages, the way a photo set as the wallpaper does.
         *
         * Every extra pixel is one more to paint on and to carry, so this is kept to the
         * least that reads as movement rather than to whatever the launcher would take.
         */
        private fun pieceWidth() = (surfaceWidth * PARALLAX).toInt()

        private fun releasePiece() {
            // While a piece is being painted, `image` is its own bitmap, so there is only
            // ever the one to let go of.
            val bitmap = image
            piece = null
            image = null
            bitmap?.recycle()
        }

        private fun startNewPiece() {
            if (surfaceWidth == 0 || surfaceHeight == 0) return
            releasePiece()

            val random = Random(System.nanoTime())
            val palette = Palette.resolve(settings.paletteKey, this@AttractorsWallpaperService, random)
            val config = settings.pieceConfig(palette)
            val new = AttractorPiece(
                width = pieceWidth(),
                visibleWidth = surfaceWidth,
                height = surfaceHeight,
                pixelRatio = resources.displayMetrics.density,
                config = config,
                typeface = if (config.text.isEmpty()) null else typeface,
                seed = random.nextLong(),
            )
            if (!pendingTapX.isNaN()) {
                new.addTapAttractor(pendingTapX, pendingTapY)
                pendingTapX = Float.NaN
            }
            piece = new
            image = new.bitmap
            pieceColors = palette
            startedAtRealtime = SystemClock.elapsedRealtime()
            savedToDisk = false
            lastFrameUptime = 0L
            nextFrameUptime = 0L

            blit()
            publishColors()
            startAnimating()
        }

        /**
         * Paints the new piece a double tap asks for, carrying the large attractor of the
         * tap when the setting calls for one.
         *
         * The point comes in as the screen's, and the piece is wider than the screen and
         * slid by however far the home screen is paged, so it is moved by that same slack.
         *
         * Runs on the render thread.
         */
        private fun startNewPieceFromTap(screenX: Float, screenY: Float) {
            if (settings.tapAttractor && surfaceWidth != 0) {
                pendingTapX = screenX + (pieceWidth() - surfaceWidth) * xOffset
                pendingTapY = screenY
            }
            startNewPiece()
        }

        /**
         * Shows the finished piece kept on disk, if there is one that still fits the screen
         * and is still in the colors the settings call for. Restoring is what makes coming
         * back from a reboot, or from the system reclaiming the service, cost one file read
         * rather than painting the whole piece over again.
         */
        private fun startFromDisk(): Boolean {
            if (isPreview) return false
            val state = savedState()
            if (state.width != pieceWidth() || state.height != surfaceHeight) return false
            // Not just the size: a piece painted before the settings were changed is not
            // the piece the settings ask for now.
            if (state.look != settings.lookSignature()) return false
            // The random palette is meant to differ from one piece to the next, so any saved
            // piece will do. Every other choice has one right answer — the system's colors,
            // in particular, follow the theme the device is in — and the saved piece has to
            // already be painted in it.
            val wanted = Palette.fixed(settings.paletteKey, this@AttractorsWallpaperService)
            if (wanted != null && state.colors != wanted.colorSum()) return false
            // Null for a random piece, which is right: its colors came from nowhere but
            // itself, so there is nothing to hold them back from being published.
            pieceColors = wanted

            val restored = try {
                BitmapFactory.decodeFile(pieceFile().path, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })?.apply { setHasAlpha(false) }
            } catch (error: Throwable) {
                Log.w(TAG, "Could not read the saved piece", error)
                null
            } ?: return false

            image = restored
            piece = null
            savedToDisk = true
            // Keep counting from when the piece was first painted, not from this restore.
            startedAtRealtime = SystemClock.elapsedRealtime() - state.ageMillis
            blit()
            publishColors()
            return true
        }

        /** Writes the piece to disk once it is finished, so that it survives a restart. */
        private fun saveToDiskIfFinished() {
            val bitmap = image ?: return
            if (savedToDisk || piece != null || isPreview || bitmap.isRecycled) return
            savedToDisk = true
            try {
                pieceFile().outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                stateStore().edit()
                    .putInt(STATE_WIDTH, image?.width ?: 0)
                    .putInt(STATE_HEIGHT, surfaceHeight)
                    .putInt(STATE_LOOK, settings.lookSignature())
                    .putInt(STATE_COLORS, pieceColors?.colorSum() ?: 0)
                    .putLong(STATE_PAINTED_AT, System.currentTimeMillis())
                    .apply()
            } catch (error: Throwable) {
                Log.w(TAG, "Could not save the piece", error)
            }
        }

        private fun startAnimating() {
            if (animating || piece == null || !visible || !screenOn) return
            animating = true
            lastFrameUptime = 0L
            nextFrameUptime = SystemClock.uptimeMillis()
            scheduleFrame()
        }

        private fun stopAnimating() {
            animating = false
            renderHandler.removeCallbacks(frameRunnable)
        }

        private fun scheduleFrame() {
            if (!animating) return
            renderHandler.removeCallbacks(frameRunnable)
            // Pace the frames from the previous target rather than from now, so that a slow
            // frame does not push every following one back.
            nextFrameUptime = max(nextFrameUptime + frameInterval(), SystemClock.uptimeMillis())
            renderHandler.postAtTime(frameRunnable, nextFrameUptime)
        }

        private val frameRunnable = Runnable { drawFrame() }

        private fun drawFrame() {
            val current = piece
            if (!animating || current == null || !visible || !screenOn) {
                animating = false
                return
            }

            val now = SystemClock.uptimeMillis()
            // On the first frame, and after a pause, advance by one frame rather than by
            // however long the wallpaper was away: the piece should not jump.
            val elapsed = if (lastFrameUptime == 0L) frameInterval() else min(now - lastFrameUptime, MAX_CATCH_UP_MILLIS)
            lastFrameUptime = now

            current.advance(elapsed.toFloat())
            blit()

            val duration = settings.durationMillis
            if (duration > 0L && current.elapsedMillis >= duration) {
                // The piece is done: drop the simulation, keep the image, and stop the loop.
                // From here on the wallpaper costs nothing at all.
                animating = false
                image = current.bitmap
                piece = null
                saveToDiskIfFinished()
                publishColors()
            } else {
                scheduleFrame()
            }
        }

        /** Milliseconds between two frames, stretched while the device is saving power. */
        private fun frameInterval(): Long {
            if (settings.respectBatterySaver && isPowerSaveMode()) return POWER_SAVE_FRAME_MILLIS
            return settings.frameIntervalMillis
        }

        /**
         * Copies the piece onto the surface.
         *
         * This is what a frame costs most — a whole screen of pixels — so it goes through
         * the GPU rather than the CPU. `lockHardwareCanvas` hands back a buffer whose
         * contents are undefined, which costs nothing here: every frame paints the piece
         * over the whole surface anyway.
         */
        private fun blit() {
            val bitmap = image ?: return
            if (bitmap.isRecycled) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = if (hardwareCanvasWorks) {
                    holder.lockHardwareCanvas() ?: holder.lockCanvas()
                } else {
                    holder.lockCanvas()
                }
                // The piece is wider than the surface; slide it by the slack, so that the
                // left edge shows on the first home screen page and the right edge on the last.
                val slack = (bitmap.width - surfaceWidth).toFloat()
                canvas?.drawBitmap(bitmap, -slack * xOffset, 0f, null)
            } catch (error: Throwable) {
                Log.w(TAG, "Could not draw on the surface", error)
                hardwareCanvasWorks = false
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (error: Throwable) {
                        Log.w(TAG, "Could not post the frame", error)
                    }
                }
            }
        }

        private fun pieceFile() = File(filesDir, PIECE_FILE)

        private fun stateStore(): SharedPreferences =
            getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

        private fun savedState(): SavedState {
            val store = stateStore()
            val paintedAt = store.getLong(STATE_PAINTED_AT, 0L)
            if (paintedAt == 0L || !pieceFile().exists()) return SavedState(0, 0, 0, 0, 0L)
            return SavedState(
                store.getInt(STATE_WIDTH, 0),
                store.getInt(STATE_HEIGHT, 0),
                store.getInt(STATE_LOOK, 0),
                store.getInt(STATE_COLORS, 0),
                max(0L, System.currentTimeMillis() - paintedAt),
            )
        }
    }

    /** What is known about the piece kept on disk. */
    private class SavedState(
        val width: Int,
        val height: Int,
        /** [Settings.lookSignature] of the settings the piece was painted with. */
        val look: Int,
        /** The three colors of the piece, added up: enough to tell one palette from another. */
        val colors: Int,
        val ageMillis: Long,
    )

    private fun isPowerSaveMode(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode == true

    private companion object {
        const val TAG = "Attractors"

        /**
         * How much wider than the screen a piece is painted, to have something to slide.
         * A third of a screen is a clear shift as the home screen pages, and a third more
         * pixels to paint on, which is what it costs.
         */
        const val PARALLAX = 1.33f
        const val FONT_ASSET = "fonts/cambam_stick_2.ttf"

        const val PIECE_FILE = "piece.png"
        const val STATE_PREFS = "piece_state"
        const val STATE_WIDTH = "width"
        const val STATE_HEIGHT = "height"
        const val STATE_LOOK = "look"
        const val STATE_COLORS = "colors"
        const val STATE_PAINTED_AT = "painted_at"

        /** A "tap" command this soon after a touch event is that same tap, reported twice. */
        const val TOUCH_ECHO_MILLIS = 100L
        const val DOUBLE_TAP_MILLIS = 500L

        /**
         * Longest stretch of piece time a single frame may cover. Coming back after a
         * pause, the piece carries on from where it was rather than fast forwarding.
         */
        const val MAX_CATCH_UP_MILLIS = 250L

        /**
         * Frame interval while the device is in battery saver. Only the frames get rarer:
         * the piece still takes the same time to paint, it is just watched less often, and
         * copying it to the screen is what a frame costs most.
         */
        const val POWER_SAVE_FRAME_MILLIS = 250L
    }
}
