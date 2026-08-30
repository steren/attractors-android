package fr.steren.attractors

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One piece: particles flowing through a field of attractors, painted on a bitmap.
 *
 * This is a port of `attractors.js` from https://github.com/steren/attractors. The
 * bitmap is never cleared, the piece *is* what the trails add up to over the frames,
 * so [step] only ever paints the segments and the shadows of a single frame onto it.
 *
 * Everything the render loop touches lives in primitive arrays allocated once, so that a
 * frame allocates nothing and never wakes the garbage collector.
 */
class AttractorPiece(
    /** Width of the piece, in device pixels. Wider than the screen, to slide across it. */
    private val width: Int,
    /** Width of the piece that is on screen at once, in device pixels. */
    private val visibleWidth: Int,
    /** Height of the piece, in device pixels. */
    private val height: Int,
    /** Device pixels in a CSS pixel, i.e. `DisplayMetrics.density`. */
    private val pixelRatio: Float,
    val config: PieceConfig,
    private val typeface: Typeface?,
    /**
     * Places particles are kept out of, as `left, top, right, bottom, radius` boxes: a
     * zone is everything within `radius` of its box. A box that is a point makes a circle
     * of it, one that is a line a rounded bar, and the nearest point on either is a clamp.
     * See [circleNoGoZone].
     */
    private val noGoZones: FloatArray = FloatArray(0),
    seed: Long,
) {
    companion object {
        /** Size of the area a shadow covers, in CSS pixels. */
        private const val SHADOW_SIZE = 16f
        /** Radius of a shadow, as a fraction of the area it covers. */
        private const val SHADOW_RADIUS_RATIO = 0.196f
        /** Offset of the area a shadow covers from its particle, in CSS pixels. */
        private const val SHADOW_OFFSET_X = 1f
        private const val SHADOW_OFFSET_Y = 1f

        /** Framerate the animation is tuned for. Every "per frame" constant refers to it. */
        private const val REFERENCE_FRAMERATE = 60f
        private const val REFERENCE_FRAME_DURATION = 1000f / REFERENCE_FRAMERATE
        /**
         * Speed of the particles at `speed` 1, in CSS pixels per second. At the reference
         * framerate, this moves a particle by one pixel per frame.
         */
        private const val SPEED = REFERENCE_FRAMERATE
        /**
         * Longest frame duration taken into account, in milliseconds. Longer frames are
         * clamped: moving the particles by the whole elapsed distance at once would draw
         * long straight segments instead of curves.
         */
        private const val MAX_FRAME_DURATION = 3f * REFERENCE_FRAME_DURATION

        /**
         * How far out from a no go zone its field reaches, in CSS pixels.
         *
         * The text uses the gaussian the web version defaults to, which never quite ends,
         * so a piece with zones all over it would be under one field or another
         * everywhere. These take the other falloff the web version offers for its circles
         * instead: a raised cosine, full at the outline and exactly nothing beyond this.
         */
        private const val NOGO_IMPACT = 24f
        /** How many times a particle may be drawn again for landing in a no go zone. */
        private const val SEED_ATTEMPTS = 16
        /**
         * Radius of the attractor a double tap places, as a fraction of the image, and the
         * weight it is given -- the strongest there is, so that it takes the piece over
         * where it lands rather than joining the crowd.
         */
        private const val TAP_ATTRACTOR_RADIUS = 0.3f

        /** A no go zone around a point: everything within [radius] of it. */
        fun circleNoGoZone(x: Float, y: Float, radius: Float) =
            floatArrayOf(x, y, x, y, radius)

        private const val DEFAULT_IMPACT_DISTANCE = 1f / 400f
        private const val ATTRACTOR_RADIUS_MIN = 1f / 50f
        private const val ATTRACTOR_RADIUS_MAX = 16f * ATTRACTOR_RADIUS_MIN
        private const val PROBABILITY_POINT_APPEARS_NEAR_TEXT = 0.2f

        /** Space left on each side of a text that had to be shrunk, as a fraction of the width. */
        private const val TEXT_MARGIN = 0.04f

        /**
         * Beyond this many standard deviations, an attractor is left out of the field.
         * Its weight there is `exp(-9)`, about one ten-thousandth of its weight at its
         * center, which no particle path shows. Skipping it saves the square root and the
         * exponential for the attractors a particle is far from, which is most of them.
         */
        private const val ATTRACTOR_CUTOFF2 = 9f

        /**
         * `exp(-t)` for `t` in `[0, ATTRACTOR_CUTOFF2]`, sampled and interpolated.
         *
         * The field is normalized right after, so the error of the interpolation — below
         * one part in a million with this many samples — cannot show up on screen, and
         * this runs several times faster than `Math.exp` on the hot loop.
         */
        private const val EXP_SAMPLES = 1024
        private val EXP_TABLE = FloatArray(EXP_SAMPLES + 2) {
            exp(-(it * ATTRACTOR_CUTOFF2 / EXP_SAMPLES).toDouble()).toFloat()
        }

        /**
         * Luminance a dark piece's wash comes to rest at, out of 255.
         *
         * Low enough that the piece still reads as black — a panel lights these pixels to
         * about a tenth of the trails — and high enough that a gap one pixel wide between
         * two trails is no longer the maximum contrast the screen can produce, which is
         * what the eye picks up as grain.
         */
        private const val WASH_LUMA = 22f

        private fun luma(color: Int) = 0.2126f * Color.red(color) +
            0.7152f * Color.green(color) + 0.0722f * Color.blue(color)

        /**
         * The color the shadow layer walks the background to.
         *
         * Downwards on a background there is room to darken, which is the web version's
         * own resting point and leaves those pieces exactly as they were.
         *
         * Upwards on one that is darker than the wash itself. A piece on black is
         * otherwise bare trails with nothing between them: every gap is pure black against
         * a bright trail, and at a line width below one pixel those gaps are single
         * pixels, which is the grain. Painted the other way up, the same layer that gives
         * a light piece its depth gives a dark one something between its trails — in the
         * piece's own colors, dimmed to [WASH_LUMA], so it reads as the same material
         * rather than as fog.
         *
         * In between sits a background with no room to darken that is still lighter than
         * the wash — `forest`, say. Washing it would darken it, and by far more than a
         * shadow ever would, so it is left with no layer at all, which is what the web
         * version does with it too.
         */
        private fun shadowRestColor(config: PieceConfig): Int {
            val background = config.backgroundColor
            val floor = 0.5f / config.shadowOpacity
            val red = min(Color.red(background).toFloat(), floor)
            val green = min(Color.green(background).toFloat(), floor)
            val blue = min(Color.blue(background).toFloat(), floor)
            val room = max(
                Color.red(background) - red,
                max(Color.green(background) - green, Color.blue(background) - blue),
            )
            if (room >= 1f) return Color.rgb(red.toInt(), green.toInt(), blue.toInt())
            if (luma(background) >= WASH_LUMA) return background

            // The average of the two trail colors, brought down to the resting luminance.
            val tint = Color.rgb(
                (Color.red(config.color1) + Color.red(config.color2)) / 2,
                (Color.green(config.color1) + Color.green(config.color2)) / 2,
                (Color.blue(config.color1) + Color.blue(config.color2)) / 2,
            )
            val tintLuma = luma(tint)
            // Trails with no light in them at all have no dimmed version to wash with.
            if (tintLuma < 1f) return background
            val scale = WASH_LUMA / tintLuma
            return Color.rgb(
                (Color.red(tint) * scale).roundToInt().coerceIn(0, 255),
                (Color.green(tint) * scale).roundToInt().coerceIn(0, 255),
                (Color.blue(tint) * scale).roundToInt().coerceIn(0, 255),
            )
        }

        /** `exp(-t)`, for a positive `t`. */
        private fun negExp(t: Float): Float {
            if (t >= ATTRACTOR_CUTOFF2) return 0f
            val s = t * (EXP_SAMPLES / ATTRACTOR_CUTOFF2)
            val i = s.toInt()
            val f = s - i
            return EXP_TABLE[i] + (EXP_TABLE[i + 1] - EXP_TABLE[i]) * f
        }
    }

    private val random = Random(seed)

    /**
     * The piece painted so far. Frames add to it, nothing ever clears it.
     *
     * It is marked opaque: every pixel is painted by the background, so copying it to the
     * screen — the most expensive thing a frame does — needs no blending.
     */
    val bitmap: Bitmap = createBitmap(width, height).apply { setHasAlpha(false) }
    private val canvas = Canvas(bitmap)

    /** Characteristic distance of the image. */
    private val d = max(width, height).toFloat()

    // Attractors: position, weight between -1 and 1, and impact distance.
    private var attractorX = FloatArray(0)
    private var attractorY = FloatArray(0)
    private var attractorWeight = FloatArray(0)
    /** `1 / radius²`, which is how the field uses the radius. */
    private var attractorInvRadius2 = FloatArray(0)
    /** `ATTRACTOR_CUTOFF2 * radius²`: past this squared distance the attractor is skipped. */
    private var attractorCutoff2 = FloatArray(0)

    /**
     * Special attractors, used for the text: segments going from `(x1, y1)` to
     * `(x1 + dx, y1 + dy)`, with `invLength2` being `1 / (dx² + dy²)`.
     */
    private var specialX1 = FloatArray(0)
    private var specialY1 = FloatArray(0)
    private var specialDx = FloatArray(0)
    private var specialDy = FloatArray(0)
    private var specialInvLength2 = FloatArray(0)
    private var specialCount = 0
    /** Impact distance shared by every special attractor of the text. */
    private var specialImpactDistance = 0f
    /** Bounding box of the special attractors, already grown by `d / 8`. */
    private var specialLeft = 0f
    private var specialTop = 0f
    private var specialRight = 0f
    private var specialBottom = 0f

    private val noGoImpact = NOGO_IMPACT * pixelRatio

    /** Whether there are any no go zones at all, so that the field can skip them. */
    private val hasNoGoZones = noGoZones.isNotEmpty()

    /** Positions of the particles. */
    private lateinit var pointsX: FloatArray
    private lateinit var pointsY: FloatArray

    /** Number of consecutive particles sharing a color. */
    private var colorSize = 0
    private val colors = intArrayOf(config.color1, config.color2)

    /**
     * Segments of the frame being painted, as the `x0, y0, x1, y1` quadruples that
     * `Canvas.drawLines` takes: a whole color group goes down in a single call.
     */
    private lateinit var segments: FloatArray
    /** Centers of the shadows of the frame being painted, as `x, y` pairs. */
    private lateinit var shadows: FloatArray

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = config.lineWidth * pixelRatio
    }

    /**
     * Shadows come to rest instead of piling up to black.
     *
     * The web version stamps a nearly transparent black onto an 8 bit canvas, and the
     * rounding of that canvas is what stops them: a channel darkens by one step for as
     * long as `shadow_opacity * channel` rounds up to 1, so it comes to rest at
     * `0.5 / shadow_opacity` and never goes below. A channel already darker than that
     * never moves at all, which is why a dark background takes no shadows there.
     *
     * Skia rounds the other way, and the very same stamps would take an area all the way
     * to black. So the limit is made explicit here: the layer is painted in the color it
     * comes to rest on — see [shadowRestColor], which is also where a background too dark
     * to darken gets the same layer the other way up — at the alpha that walks there at
     * the same pace, about one 8 bit step per stamp.
     */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // `drawPoints` with a round cap stamps a disc as wide as the stroke.
        strokeCap = Paint.Cap.ROUND

        val background = config.backgroundColor
        val rest = shadowRestColor(config)
        color = rest

        // How far the channel with the furthest to go has to travel, which sets the pace.
        val gap = max(
            abs(Color.red(background) - Color.red(rest)),
            max(
                abs(Color.green(background) - Color.green(rest)),
                abs(Color.blue(background) - Color.blue(rest)),
            ),
        )
        alpha = if (gap < 1) 0 else (255f / gap).roundToInt().coerceIn(1, 255)
    }

    /** Whether the background is far enough from the resting color for the layer to register. */
    private val hasShadows = shadowPaint.alpha > 0

    private val shadowRadius: Float
    private val shadowOffsetX: Float
    private val shadowOffsetY: Float

    /** Time the piece has been animating for, in milliseconds of piece time. */
    var elapsedMillis = 0f
        private set

    init {
        val shadowSize = SHADOW_SIZE * pixelRatio * config.shadowScale
        shadowRadius = SHADOW_RADIUS_RATIO * shadowSize
        shadowPaint.strokeWidth = 2f * shadowRadius
        // The shadow is centered where the sprite that it replaces used to be centered.
        shadowOffsetX = shadowSize / 2f - SHADOW_OFFSET_X * pixelRatio
        shadowOffsetY = shadowSize / 2f - SHADOW_OFFSET_Y * pixelRatio

        canvas.drawColor(config.backgroundColor)

        initAttractors()

        val seedX = FloatBag()
        val seedY = FloatBag()
        if (config.text.isNotEmpty() && typeface != null) {
            initTextSpecialAttractors(seedX, seedY)
        }
        // Seeds the remaining particles.
        initPoints(seedX, seedY)

        pointsX = seedX.toArray()
        pointsY = seedY.toArray()
        val total = pointsX.size
        colorSize = if (total == 0) 0 else ceilDiv(total, colors.size)
        segments = FloatArray(4 * total)
        shadows = FloatArray(2 * total)
    }

    /** Number of particles in the piece. */
    val particleCount: Int get() = pointsX.size

    /**
     * Advances the piece by [pieceMillis] of piece time, painting it onto [bitmap].
     *
     * Long advances are cut into short steps rather than clamped: how often the piece is
     * shown then costs nothing but the frames it is shown in, and a piece takes the same
     * wall clock time to paint whether it is watched at 60 frames per second or at 4.
     */
    fun advance(pieceMillis: Float) {
        var remaining = pieceMillis
        while (remaining > 0f) {
            val chunk = min(remaining, MAX_FRAME_DURATION)
            step(chunk)
            remaining -= chunk
        }
    }

    /**
     * Paints one frame onto [bitmap].
     *
     * @param frameMillis Wall clock time since the previous frame. Distances and
     *   probabilities are scaled by it, so that the piece animates at the same speed
     *   whatever the framerate.
     */
    private fun step(frameMillis: Float) {
        val total = pointsX.size
        if (total == 0) return

        // Time the piece advances by during this frame: how long the frame lasted, scaled
        // by the configured speed. Everything below is derived from it, so that a speed of
        // 2 plays the very same animation twice as fast.
        val elapsed = min(frameMillis, MAX_FRAME_DURATION) * config.speed
        elapsedMillis += elapsed

        // Duration of this frame, expressed as a number of reference frames.
        val frames = elapsed / REFERENCE_FRAME_DURATION
        // Distance travelled by a particle during this frame.
        val step = SPEED * elapsed * pixelRatio / 1000f

        var shadowCount = 0

        // Cut the particles into one group per color and paint each group at once: fill
        // the segment buffer, then hand the whole group to a single `drawLines`. This
        // performs much better than painting each segment one after the other.
        for (c in colors.indices) {
            val start = c * colorSize
            val end = min(start + colorSize, total)
            if (start >= end) break

            var s = 0
            for (i in start until end) {
                val oldX = pointsX[i]
                val oldY = pointsY[i]
                fieldAt(oldX, oldY)

                // The particle moves perpendicular to the field, which is what makes it
                // circle an attractor instead of falling into it.
                val newX = oldX - step * fieldY
                val newY = oldY + step * fieldX

                segments[s++] = oldX
                segments[s++] = oldY
                segments[s++] = newX
                segments[s++] = newY

                // If the field is weak, reduce the probability to draw a shadow. Shadows
                // are drawn once per frame, so scale it by the time the frame covers, to
                // keep the same amount of shadows whatever the framerate and the speed.
                if (hasShadows && random.nextFloat() <= (fieldX * fieldX + fieldY * fieldY) * frames) {
                    shadows[shadowCount++] = newX + shadowOffsetX
                    shadows[shadowCount++] = newY + shadowOffsetY
                }

                pointsX[i] = newX
                pointsY[i] = newY
            }

            linePaint.color = colors[c]
            canvas.drawLines(segments, 0, s, linePaint)
        }

        if (shadowCount > 0) {
            canvas.drawPoints(shadows, 0, shadowCount, shadowPaint)
        }
    }

    // Scratch values of `fieldAt`, so that it returns a vector without allocating one.
    private var fieldX = 0f
    private var fieldY = 0f

    /** Normalized vector of the field at a point, left in [fieldX] and [fieldY]. */
    private fun fieldAt(x: Float, y: Float) {
        var ux = 0f
        var uy = 0f

        for (a in attractorX.indices) {
            val dx = x - attractorX[a]
            val dy = y - attractorY[a]
            val d2 = dx * dx + dy * dy
            // Too far to matter, or exactly on the attractor, where there is no direction.
            if (d2 >= attractorCutoff2[a] || d2 == 0f) continue

            val weight = attractorWeight[a] * negExp(d2 * attractorInvRadius2[a])
            val invD = weight / sqrt(d2)
            ux += invD * dx
            uy += invD * dy
        }

        val norm = sqrt(ux * ux + uy * uy)
        if (norm > 1e-8f) {
            ux /= norm
            uy /= norm
        } else {
            ux = 0f
            uy = 0f
        }

        // If we are near a special attractor, add its contribution to the field.
        if (specialCount > 0 && x > specialLeft && x < specialRight && y > specialTop && y < specialBottom) {
            findClosestPointOnSpecialAttractor(x, y)
            var textUx = x - closestX
            var textUy = y - closestY
            val textNorm = sqrt(textUx * textUx + textUy * textUy)
            if (textNorm > 1e-8f) {
                textUx /= textNorm
                textUy /= textNorm

                val textWeight = negExp(closestDistance2 / (specialImpactDistance * d * d))
                ux = (1f - textWeight) * ux + textWeight * textUx
                uy = (1f - textWeight) * uy + textWeight * textUy
            }
        }

        // And if we are near a no go zone, its own contribution goes on top, so that a
        // zone wins over the text where the two meet.
        if (hasNoGoZones) {
            // The nearest zone, out of the handful there are. A zone whose box is further
            // off than its reach in x or y alone cannot be it, which rejects all but the
            // one or two a particle stands near for a couple of compares.
            var nearestX = 0f
            var nearestY = 0f
            var nearestDistance = 0f
            var nearestRadius = 0f
            var closest = Float.MAX_VALUE
            for (i in noGoZones.indices step 5) {
                val radius = noGoZones[i + 4]
                val reach = radius + noGoImpact
                val left = noGoZones[i]
                val right = noGoZones[i + 2]
                if (x < left - reach || x > right + reach) continue
                val top = noGoZones[i + 1]
                val bottom = noGoZones[i + 3]
                if (y < top - reach || y > bottom + reach) continue

                // The nearest point of the box, which the zone is drawn around.
                val dx = x - x.coerceIn(left, right)
                val dy = y - y.coerceIn(top, bottom)
                val distance = sqrt(dx * dx + dy * dy)
                val gap = abs(distance - radius)
                if (gap < closest) {
                    closest = gap
                    nearestX = dx
                    nearestY = dy
                    nearestDistance = distance
                    nearestRadius = radius
                }
            }

            // Right on the middle of one there is no direction to be pushed in.
            if (closest < noGoImpact && nearestDistance > 1e-6f) {
                // Away from the outline: outwards when outside it, inwards when within.
                val side = if (nearestDistance >= nearestRadius) 1f else -1f
                val zoneUx = side * nearestX / nearestDistance
                val zoneUy = side * nearestY / nearestDistance
                // The raised cosine of the web version's circles: 1 on the outline, and 0
                // at the impact distance, where it also flattens out rather than cutting.
                val zoneWeight = 0.5f * (1f + cos(PI.toFloat() * closest / noGoImpact))
                ux = (1f - zoneWeight) * ux + zoneWeight * zoneUx
                uy = (1f - zoneWeight) * uy + zoneWeight * zoneUy
            }
        }

        fieldX = ux
        fieldY = uy
    }

    /**
     * Whether a point is inside a no go zone, which is where a particle may not be seeded.
     *
     * A particle never crosses into one once it is running -- on the outline the field is
     * entirely the zone's own, and a particle travels at a right angle to the field, so it
     * runs around the zone rather than into it -- but one seeded inside would be trapped
     * there, painting a scribble in the middle of a space meant to stay bare.
     */
    private fun inNoGoZone(x: Float, y: Float): Boolean {
        for (i in noGoZones.indices step 5) {
            val radius = noGoZones[i + 4]
            if (x < noGoZones[i] - radius || x > noGoZones[i + 2] + radius) continue
            if (y < noGoZones[i + 1] - radius || y > noGoZones[i + 3] + radius) continue
            val dx = x - x.coerceIn(noGoZones[i], noGoZones[i + 2])
            val dy = y - y.coerceIn(noGoZones[i + 1], noGoZones[i + 3])
            if (dx * dx + dy * dy < radius * radius) return true
        }
        return false
    }

    // Scratch values of `findClosestPointOnSpecialAttractor`.
    private var closestX = 0f
    private var closestY = 0f
    private var closestDistance2 = 0f

    /**
     * Finds the point closest to `x, y` on any special attractor segment. Squared
     * distances are compared rather than distances: the winner is the same, for one square
     * root at the end instead of one per segment.
     */
    private fun findClosestPointOnSpecialAttractor(x: Float, y: Float) {
        var best = Float.MAX_VALUE
        for (s in 0 until specialCount) {
            val x1 = specialX1[s]
            val y1 = specialY1[s]
            val dx = specialDx[s]
            val dy = specialDy[s]

            // Position of the projection of (x, y) on the segment, clamped to its ends.
            val t = (((x - x1) * dx + (y - y1) * dy) * specialInvLength2[s]).coerceIn(0f, 1f)
            val originX = x1 + dx * t
            val originY = y1 + dy * t
            val errorX = originX - x
            val errorY = originY - y
            val distance2 = errorX * errorX + errorY * errorY
            if (distance2 < best) {
                best = distance2
                closestX = originX
                closestY = originY
            }
        }
        closestDistance2 = best
    }

    private fun initAttractors() {
        val count = config.nbAttractors
        attractorX = FloatArray(count)
        attractorY = FloatArray(count)
        attractorWeight = FloatArray(count)
        attractorInvRadius2 = FloatArray(count)
        attractorCutoff2 = FloatArray(count)

        val minRadius = ATTRACTOR_RADIUS_MIN * d
        val maxRadius = ATTRACTOR_RADIUS_MAX * d

        for (a in 0 until count) {
            attractorX[a] = random.nextFloat() * (width - 1)
            attractorY[a] = random.nextFloat() * (height - 1)
            attractorWeight[a] = random.nextFloat() * 2f - 1f
            val radius = random.nextFloat() * (maxRadius - minRadius) + minRadius
            attractorInvRadius2[a] = 1f / (radius * radius)
            attractorCutoff2[a] = ATTRACTOR_CUTOFF2 * radius * radius
        }
    }

    /**
     * Adds one more attractor to the field, at a point of the piece.
     *
     * The arrays are copied rather than kept with room to spare: this is a double tap, not
     * the hot loop, and a piece is normally built once and left alone.
     */
    private fun addAttractor(x: Float, y: Float, radius: Float, weight: Float) {
        val a = attractorX.size
        attractorX = attractorX.copyOf(a + 1)
        attractorY = attractorY.copyOf(a + 1)
        attractorWeight = attractorWeight.copyOf(a + 1)
        attractorInvRadius2 = attractorInvRadius2.copyOf(a + 1)
        attractorCutoff2 = attractorCutoff2.copyOf(a + 1)

        attractorX[a] = x
        attractorY[a] = y
        attractorWeight[a] = weight
        attractorInvRadius2[a] = 1f / (radius * radius)
        attractorCutoff2[a] = ATTRACTOR_CUTOFF2 * radius * radius
    }

    /**
     * Puts the large attractor of a double tap at a point of the piece, which the trails
     * wind around from the first frame on.
     */
    fun addTapAttractor(x: Float, y: Float) {
        addAttractor(x, y, TAP_ATTRACTOR_RADIUS * d, if (random.nextBoolean()) 1f else -1f)
    }

    /** @param particleDensity Number of particles for a square of 1000 * 1000 screen pixels. */
    private fun initPoints(outX: FloatBag, outY: FloatBag) {
        val screenWidth = width / pixelRatio
        val screenHeight = height / pixelRatio
        // For a device with a higher pixel ratio, put more particles, but not
        // pixelRatio * pixelRatio more of them, for performance reasons.
        val count =
            (pixelRatio * config.particleDensity * screenWidth * screenHeight / 1_000_000f).toInt()
        val sizeRatio = config.initScale
        for (i in 0 until count) {
            var x = 0f
            var y = 0f
            // Draw again for a particle that landed in a no go zone, as the web version
            // does -- but not forever: zones cover a small part of a piece, so a handful
            // of tries is already beyond unlucky, and a wallpaper is no place for a loop
            // that has no end.
            for (attempt in 0 until SEED_ATTEMPTS) {
                x = normalRand() * width * sizeRatio + width * (1f - sizeRatio) / 2f
                y = normalRand() * height * sizeRatio + height * (1f - sizeRatio) / 2f
                if (!hasNoGoZones || !inNoGoZone(x, y)) break
            }
            outX.add(x)
            outY.add(y)
        }
    }

    /**
     * Turns the outline of the text into special attractors, and seeds particles along it.
     *
     * The web version walks the path commands that opentype.js hands it. Here the outline
     * comes from the platform text engine, and [PathMeasure] flattens each of its contours
     * into the segments that become the attractors.
     */
    private fun initTextSpecialAttractors(seedX: FloatBag, seedY: FloatBag) {
        specialImpactDistance = DEFAULT_IMPACT_DISTANCE * pixelRatio

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@AttractorPiece.typeface
            // Letters are `1 / textWidthRatio` of the screen tall, which for the string the
            // web page shows works out to about the width of the screen.
            textSize = visibleWidth / config.textWidthRatio
        }

        /*
         * The text has to fit the strip of the piece that is on screen whatever the home
         * screen is scrolled to, which is narrower than the screen: the piece is wider than
         * the screen by the room it slides in, and the strip that is never slid past is
         * what is left when that room is taken off both ends. Sized to the screen instead,
         * the string would be cut off at either end of the scroll.
         *
         * A phone is also a lot narrower than the page this was tuned on, so a longer
         * string is shrunk to fit rather than cropped.
         */
        val alwaysOnScreen = (2 * visibleWidth - width).coerceAtLeast(visibleWidth / 2)
        val room = alwaysOnScreen * (1f - 2f * TEXT_MARGIN)
        val fullWidth = textPaint.measureText(config.text)
        if (fullWidth > room) {
            textPaint.textSize *= room / fullWidth
        }

        val path = Path()
        textPaint.getTextPath(config.text, 0, config.text.length, 0f, 0f, path)

        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.isEmpty) return

        // Place the bounding box of the outline at the requested position.
        val textX = width * config.textPositionX / 100f - bounds.centerX()
        val textY = height * config.textPositionY / 100f - bounds.centerY()

        // Boxes are grown by `d / 8`: outside of that the text pulls at nothing.
        val near = d / 8f
        specialLeft = bounds.left + textX - near
        specialTop = bounds.top + textY - near
        specialRight = bounds.right + textX + near
        specialBottom = bounds.bottom + textY + near

        // Segments short enough that the curves of the glyphs read as curves.
        val segmentLength = max(4f, textPaint.textSize / 8f)

        val x1 = FloatBag()
        val y1 = FloatBag()
        val dxs = FloatBag()
        val dys = FloatBag()

        val position = FloatArray(2)
        val measure = PathMeasure(path, false)
        do {
            val length = measure.length
            if (length <= 0f) continue
            val steps = max(1, (length / segmentLength).roundToInt())

            var previousX = 0f
            var previousY = 0f
            for (s in 0..steps) {
                measure.getPosTan(length * s / steps, position, null)
                val px = position[0] + textX
                val py = position[1] + textY
                if (s > 0) {
                    val dx = px - previousX
                    val dy = py - previousY
                    if (dx != 0f || dy != 0f) {
                        x1.add(previousX)
                        y1.add(previousY)
                        dxs.add(dx)
                        dys.add(dy)
                    }
                }
                // Seed some particles right on the outline, so that the text is drawn from
                // its own edge and not only by whatever drifts into it.
                if (random.nextFloat() < PROBABILITY_POINT_APPEARS_NEAR_TEXT) {
                    seedX.add(px + random.nextFloat() - 0.5f)
                    seedY.add(py + random.nextFloat() - 0.5f)
                }
                previousX = px
                previousY = py
            }
        } while (measure.nextContour())

        specialX1 = x1.toArray()
        specialY1 = y1.toArray()
        specialDx = dxs.toArray()
        specialDy = dys.toArray()
        specialCount = specialX1.size
        specialInvLength2 = FloatArray(specialCount) {
            1f / (specialDx[it] * specialDx[it] + specialDy[it] * specialDy[it])
        }
    }

    /** Random number between 0 and 1, following a gaussian-ish distribution. */
    private fun normalRand(): Float {
        while (true) {
            val x = random.nextFloat()
            if (exp((-1.0 * (x - 0.5) * (x - 0.5)) / 0.1) > random.nextDouble()) return x
        }
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    fun recycle() {
        bitmap.recycle()
    }
}

/** A growable list of floats, without the boxing of an `ArrayList<Float>`. */
private class FloatBag(initialCapacity: Int = 1024) {
    private var values = FloatArray(initialCapacity)
    private var size = 0

    fun add(value: Float) {
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size++] = value
    }

    fun toArray(): FloatArray = values.copyOf(size)
}
