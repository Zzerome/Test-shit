package com.example.dial

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var dial: DialView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dial = DialView(this)
        setContentView(dial)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onDestroy() {
        dial.release()
        super.onDestroy()
    }
}

class DialView(context: Context) : View(context), Choreographer.FrameCallback {

    companion object {
        private const val TICKS = 60
        private const val STEP = 360f / TICKS     // 6 degrees between ticks
        private const val CLICK_STEP = 30f        // click on every 5th tick = 12 per turn
        private const val DRUMS = 5               // digit drums in the counter
        private const val FRICTION = 0.0020f      // bigger = brakes faster
        private const val MIN_CLICK_GAP_MS = 22L
        private const val GLOW = 1.0f             // glow strength: 0 = off, 2 = twice as strong
    }

    private val dp = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val digit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    // soft glow: wide faint strokes first, narrower brighter ones last
    private val glowExtra = floatArrayOf(7.4f, 5.6f, 4.0f, 2.6f, 1.4f)
    private val glowAlpha = floatArrayOf(0.012f, 0.018f, 0.030f, 0.040f, 0.060f)
    private val hubExtra = floatArrayOf(10f, 7.5f, 5f, 2.5f)
    private val hubAlpha = intArrayOf(6, 10, 16, 26)

    private val rect = RectF()
    private val hit = RectF()
    private val pos = DoubleArray(DRUMS)

    private var dialCx = 0f
    private var dialCy = 0f
    private var dialR = 0f
    private var counterCy = 0f
    private var counterLeft = 0f
    private var counterW = 0f
    private var cellW = 0f
    private var cellH = 0f
    private var dotGap = 0f

    private var dialAngle = 0f        // total degrees of the big hand, clockwise = plus
    private var lastTouchAngle = 0f
    private var velocity = 0f         // degrees per ms
    private var lastMoveTime = 0L
    private var flinging = false
    private var lastFrameNanos = 0L
    private var lastTick = 0
    private var lastClickTime = 0L

    private var counterTouch = false
    private var downX = 0f
    private var downY = 0f
    private val resetRunnable = Runnable { resetCounter() }

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private var clickId = 0
    private var clickReady = false

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    init {
        setBackgroundColor(Color.BLACK)
        val f = File(context.cacheDir, "click.wav")
        f.writeBytes(makeClickWav())
        soundPool.setOnLoadCompleteListener { _, _, status -> clickReady = (status == 0) }
        clickId = soundPool.load(f.absolutePath, 1)
    }

    // ---------- geometry ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val wf = w.toFloat()
        val hf = h.toFloat()
        dialR = min(wf * 0.42f, hf * 0.27f)
        dialCx = wf / 2f
        dialCy = hf * 0.63f
        cellW = min(wf * 0.095f, hf * 0.05f)
        cellH = cellW * 1.5f
        counterCy = hf * 0.25f
        dotGap = cellW * 0.35f
        counterW = DRUMS * cellW + dotGap
        counterLeft = dialCx - counterW / 2f
        digit.textSize = cellH * 0.66f
        hit.set(
            counterLeft - cellW, counterCy - cellH,
            counterLeft + counterW + cellW, counterCy + cellH
        )
    }

    // ---------- drawing primitives ----------

    private fun seg(
        canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, w: Float, a: Int
    ) {
        for (k in glowExtra.indices) {
            glow.strokeWidth = w + glowExtra[k] * dp
            glow.alpha = (a * glowAlpha[k] * GLOW).toInt().coerceIn(0, 255)
            canvas.drawLine(x1, y1, x2, y2, glow)
        }
        core.strokeWidth = w
        core.alpha = a
        canvas.drawLine(x1, y1, x2, y2, core)
    }

    // radial line: deg 0 = up, clockwise = plus
    private fun tick(
        canvas: Canvas, cx: Float, cy: Float, deg: Float,
        r1: Float, r2: Float, w: Float, a: Int = 255
    ) {
        val rad = Math.toRadians(deg.toDouble())
        val s = sin(rad).toFloat()
        val c = cos(rad).toFloat()
        seg(canvas, cx + r1 * s, cy - r1 * c, cx + r2 * s, cy - r2 * c, w, a)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        drawCounter(canvas)
        drawClock(canvas)
    }

    // ---------- clock: fixed ticks, rotating hands ----------

    private fun drawClock(canvas: Canvas) {
        for (i in 0 until TICKS) {
            val quarter = i % 15 == 0
            val major = i % 5 == 0
            val len = dialR * (if (quarter) 0.22f else if (major) 0.17f else 0.09f)
            val w = dp * (if (quarter) 3.0f else if (major) 2.2f else 1.2f)
            tick(
                canvas, dialCx, dialCy, i * STEP,
                dialR - len, dialR, w, if (major) 255 else 170
            )
        }
        // small (hour) hand: 12x slower than the big one
        tick(canvas, dialCx, dialCy, dialAngle / 12f, 0f, dialR * 0.55f, dp * 5.5f)
        // big (minute) hand: follows the finger
        tick(canvas, dialCx, dialCy, dialAngle, 0f, dialR * 0.86f, dp * 3.0f)

        // hub
        val hubR = dp * 9f
        for (k in hubExtra.indices) {
            fill.alpha = (hubAlpha[k] * GLOW).toInt().coerceIn(0, 255)
            canvas.drawCircle(dialCx, dialCy, hubR + hubExtra[k] * dp, fill)
        }
        fill.alpha = 255
        canvas.drawCircle(dialCx, dialCy, hubR, fill)
        canvas.drawCircle(dialCx, dialCy, dp * 3f, black)
    }

    // ---------- counter: odometer / water meter drums ----------

    private fun drawCounter(canvas: Canvas) {
        // value in tenths of a turn, always positive
        val x0 = (abs(dialAngle.toDouble()) / 360.0 * 10.0) % 100000.0

        // drum positions (0..10): the last drum rolls smoothly,
        // each next one rolls only while all drums to its right go 9 -> 0
        var s = x0 - floor(x0)
        var prev = floor(x0).toLong() % 10
        pos[0] = prev + s
        for (i in 1 until DRUMS) {
            val di = floor(x0 / 10.0.pow(i)).toLong() % 10
            s = if (prev == 9L) s else 0.0
            pos[i] = di + s
            prev = di
        }

        val top = counterCy - cellH / 2f
        var x = counterLeft
        for (k in 0 until DRUMS) {
            val i = DRUMS - 1 - k
            val p = pos[i]
            // leading zeros are dim, and brighten as the first digit rolls in
            val leading = i >= 2 && x0 < 10.0.pow(i)
            val bright = if (leading) 0.28f + 0.72f * p.toFloat() else 1f
            drawDrum(canvas, x, top, p, bright)
            x += cellW
            if (i == 1) {
                fill.alpha = 255
                canvas.drawCircle(x + dotGap / 2f, counterCy + cellH * 0.30f, dp * 1.8f, fill)
                x += dotGap
            }
        }
    }

    private fun drawDrum(canvas: Canvas, left: Float, top: Float, pos: Double, bright: Float) {
        canvas.save()
        rect.set(left - dp * 8f, top, left + cellW + dp * 8f, top + cellH)
        canvas.clipRect(rect)
        val base = floor(pos).toInt()
        val frac = (pos - floor(pos)).toFloat()
        val cy = top + cellH / 2f
        val step = cellH * 0.95f
        digit.setShadowLayer(dp * 6f, 0f, 0f, Color.argb((90 * GLOW).toInt().coerceIn(0, 255), 255, 255, 255))
        for (j in -1..2) {
            val n = ((base + j) % 10 + 10) % 10
            val dist = abs(j - frac)
            val a = 255f * (1f - dist * 0.85f) * bright
            digit.alpha = a.coerceIn(0f, 255f).toInt()
            val y = cy + (j - frac) * step + digit.textSize * 0.36f
            canvas.drawText(n.toString(), left + cellW / 2f, y, digit)
        }
        canvas.restore()
    }

    // ---------- touch ----------

    private fun touchAngle(x: Float, y: Float): Float =
        Math.toDegrees(atan2((y - dialCy).toDouble(), (x - dialCx).toDouble())).toFloat()

    private fun norm(d: Float): Float {
        var v = d % 360f
        if (v > 180f) v -= 360f
        if (v < -180f) v += 360f
        return v
    }

    private fun resetCounter() {
        stopFling()
        velocity = 0f
        dialAngle = 0f
        lastTick = 0
        buzz()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // touches on the counter only do the long-press reset, they never spin the dial
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            counterTouch = hit.contains(e.x, e.y)
            if (counterTouch) {
                downX = e.x
                downY = e.y
                postDelayed(resetRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
        }
        if (counterTouch) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop) {
                        removeCallbacks(resetRunnable)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(resetRunnable)
                    counterTouch = false
                }
            }
            return true
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                lastTouchAngle = touchAngle(e.x, e.y)
                velocity = 0f
                lastMoveTime = e.eventTime
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val a = touchAngle(e.x, e.y)
                val d = norm(a - lastTouchAngle)
                lastTouchAngle = a
                val dt = (e.eventTime - lastMoveTime).coerceAtLeast(1L)
                lastMoveTime = e.eventTime
                velocity = velocity * 0.6f + (d / dt) * 0.4f
                applyDelta(d)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.eventTime - lastMoveTime > 80L) velocity = 0f
                if (abs(velocity) > 0.02f) startFling()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    // ---------- rotation + inertia ----------

    private fun applyDelta(d: Float) {
        dialAngle += d
        val now = floor(dialAngle / CLICK_STEP).toInt()
        if (now != lastTick) {
            lastTick = now
            click()
        }
        invalidate()
    }

    private fun startFling() {
        flinging = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopFling() {
        if (flinging) {
            flinging = false
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!flinging) return
        if (lastFrameNanos != 0L) {
            val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000f).coerceAtMost(50f)
            applyDelta(velocity * dt)
            velocity *= exp(-FRICTION * dt)
            if (abs(velocity) < 0.01f) {
                flinging = false
                return
            }
        }
        lastFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ---------- click: sound + vibration ----------

    private fun click() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime < MIN_CLICK_GAP_MS) return
        lastClickTime = now
        if (clickReady) soundPool.play(clickId, 0.8f, 0.8f, 1, 0, 1f)
        buzz()
    }

    private fun buzz() {
        val v = vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(12L, 180))
    }

    // short synthesized "tick" sound, no audio files needed
    private fun makeClickWav(): ByteArray {
        val rate = 44100
        val n = rate * 22 / 1000
        val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        var seed = 12345
        for (i in 0 until n) {
            val t = i / rate.toFloat()
            val env = exp(-t * 260f)
            seed = seed * 1103515245 + 12345
            val noise = ((seed shr 16) and 0x7fff) / 16384f - 1f
            val tone = sin(2.0 * PI * 2000.0 * t).toFloat()
            val v = (noise * 0.55f + tone * 0.45f) * env
            pcm.putShort((v * 0.9f * Short.MAX_VALUE).toInt().toShort())
        }
        val data = pcm.array()
        val out = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray())
        out.putInt(36 + data.size)
        out.put("WAVE".toByteArray())
        out.put("fmt ".toByteArray())
        out.putInt(16)
        out.putShort(1)
        out.putShort(1)
        out.putInt(rate)
        out.putInt(rate * 2)
        out.putShort(2)
        out.putShort(16)
        out.put("data".toByteArray())
        out.putInt(data.size)
        out.put(data)
        return out.array()
    }

    fun release() {
        stopFling()
        removeCallbacks(resetRunnable)
        soundPool.release()
    }

    override fun onDetachedFromWindow() {
        stopFling()
        removeCallbacks(resetRunnable)
        super.onDetachedFromWindow()
    }
}
