package com.example.dial

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.view.WindowInsets
import android.view.WindowInsetsController
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

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
        private const val MAX_TURNS = 10          // gauge range: -10..+10 turns
        private const val GAUGE_SPAN = 100f       // gauge half-angle, degrees
        private const val FRICTION = 0.0020f      // bigger = brakes faster
        private const val MIN_CLICK_GAP_MS = 22L
    }

    private val dp = resources.displayMetrics.density

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

    private var dialCx = 0f
    private var dialCy = 0f
    private var dialR = 0f
    private var gaugeCx = 0f
    private var gaugeCy = 0f
    private var gaugeR = 0f

    private var dialAngle = 0f        // total degrees, clockwise = plus
    private var lastTouchAngle = 0f
    private var velocity = 0f         // degrees per ms
    private var lastMoveTime = 0L
    private var flinging = false
    private var lastFrameNanos = 0L
    private var lastTick = 0
    private var lastClickTime = 0L

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
        gaugeR = min(wf * 0.32f, hf * 0.16f)
        gaugeCx = wf / 2f
        gaugeCy = hf * 0.25f
    }

    // ---------- drawing ----------

    // deg: 0 = up, clockwise = plus
    private fun tick(
        canvas: Canvas, cx: Float, cy: Float, deg: Float,
        r1: Float, r2: Float, w: Float, a: Int = 255
    ) {
        val rad = Math.toRadians(deg.toDouble())
        val s = sin(rad).toFloat()
        val c = cos(rad).toFloat()
        val x1 = cx + r1 * s
        val y1 = cy - r1 * c
        val x2 = cx + r2 * s
        val y2 = cy - r2 * c

        glow.strokeWidth = w * 4.5f
        glow.alpha = (a * 0.10f).toInt()
        canvas.drawLine(x1, y1, x2, y2, glow)
        glow.strokeWidth = w * 2.2f
        glow.alpha = (a * 0.22f).toInt()
        canvas.drawLine(x1, y1, x2, y2, glow)
        core.strokeWidth = w
        core.alpha = a
        canvas.drawLine(x1, y1, x2, y2, core)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        drawGauge(canvas)
        drawDial(canvas)
    }

    private fun drawDial(canvas: Canvas) {
        for (i in 0 until TICKS) {
            val major = i % 5 == 0
            val len = dialR * (if (major) 0.17f else 0.09f)
            val w = dp * (if (major) 3.2f else 1.8f)
            tick(
                canvas, dialCx, dialCy, dialAngle + i * STEP,
                dialR - len, dialR, w, if (major) 255 else 170
            )
        }
        // fixed pointer at the top
        tick(canvas, dialCx, dialCy, 0f, dialR * 1.07f, dialR * 1.17f, dp * 3.2f)
    }

    private fun drawGauge(canvas: Canvas) {
        for (k in -MAX_TURNS..MAX_TURNS) {
            val deg = k * GAUGE_SPAN / MAX_TURNS
            val major = k % 5 == 0
            val len = gaugeR * (if (k == 0) 0.20f else if (major) 0.15f else 0.08f)
            val w = dp * (if (major) 2.6f else 1.5f)
            tick(
                canvas, gaugeCx, gaugeCy, deg,
                gaugeR - len, gaugeR, w, if (major) 255 else 150
            )
        }
        val turns = (dialAngle / 360f).coerceIn(-MAX_TURNS.toFloat(), MAX_TURNS.toFloat())
        val needleDeg = turns * GAUGE_SPAN / MAX_TURNS
        tick(canvas, gaugeCx, gaugeCy, needleDeg, gaugeR * 0.12f, gaugeR * 0.80f, dp * 2.4f)

        fill.alpha = 30
        canvas.drawCircle(gaugeCx, gaugeCy, dp * 9f, fill)
        fill.alpha = 255
        canvas.drawCircle(gaugeCx, gaugeCy, dp * 4.5f, fill)
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

    override fun onTouchEvent(e: MotionEvent): Boolean {
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
        val now = floor(dialAngle / STEP).toInt()
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
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(12L, 180))
        } else {
            v.vibrate(12L)
        }
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
        soundPool.release()
    }

    override fun onDetachedFromWindow() {
        stopFling()
        super.onDetachedFromWindow()
    }
}
