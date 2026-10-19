package com.example.mouseonlytouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Phủ một lớp trong suốt lên toàn màn hình để nuốt mọi cú chạm của ngón tay.
 * Sự kiện từ chuột được dịch lại thành cử chỉ (tap / long-press / swipe) rồi
 * gửi xuống app bên dưới bằng dispatchGesture().
 */
class MouseOnlyService : AccessibilityService() {

    companion object {
        const val PREFS = "mouse_only_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_STATUS = "status"

        const val STATUS_BLOCKING = "blocking"
        const val STATUS_NO_MOUSE = "no_mouse"
        const val STATUS_DISABLED = "disabled"
        const val STATUS_PAUSED = "paused"
        const val STATUS_STOPPED = "stopped"

        // Chờ lớp phủ chuyển sang "không chạm được" rồi mới bấm xuống app bên dưới.
        private const val SETTLE_MS = 60L
        private const val TAP_MS = 50L
        private const val LONG_PRESS_HOLD_MS = 450L
        private const val LONG_PRESS_MS = 650L
        private const val SCROLL_MS = 250L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var inputManager: InputManager
    private lateinit var prefs: SharedPreferences

    private var overlay: MouseCaptureView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var inFlight = 0

    private var paused = false
    private var volUpHeld = false
    private var volDownHeld = false
    private var comboFired = false

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refresh()
        override fun onInputDeviceRemoved(deviceId: Int) = refresh()
        override fun onInputDeviceChanged(deviceId: Int) = refresh()
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_ENABLED) refresh()
    }

    // ---------------------------------------------------------------- vòng đời

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        inputManager.registerInputDeviceListener(deviceListener, handler)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        hideOverlay()
        if (::inputManager.isInitialized) {
            inputManager.unregisterInputDeviceListener(deviceListener)
        }
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            prefs.edit().putString(KEY_STATUS, STATUS_STOPPED).apply()
        }
    }

    // ------------------------------------------------------- bật / tắt lớp phủ

    private fun refresh() {
        val enabled = prefs.getBoolean(KEY_ENABLED, true)
        val status = when {
            !enabled -> STATUS_DISABLED
            paused -> STATUS_PAUSED
            !hasMouse() -> STATUS_NO_MOUSE
            else -> STATUS_BLOCKING
        }
        if (status == STATUS_BLOCKING) showOverlay() else hideOverlay()
        prefs.edit().putString(KEY_STATUS, status).apply()
    }

    private fun hasMouse(): Boolean = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id)
        d != null &&
            !d.isVirtual &&
            d.supportsSource(InputDevice.SOURCE_MOUSE) &&
            !d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)
    }

    private fun showOverlay() {
        if (overlay != null) return
        val view = MouseCaptureView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            windowManager.addView(view, lp)
            overlay = view
            overlayParams = lp
        } catch (e: Exception) {
            overlay = null
            overlayParams = null
        }
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        try {
            windowManager.removeView(v)
        } catch (e: Exception) {
            // đã bị gỡ rồi
        }
        overlay = null
        overlayParams = null
    }

    private fun setTouchable(touchable: Boolean) {
        val v = overlay ?: return
        val lp = overlayParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        lp.flags = if (touchable) lp.flags and flag.inv() else lp.flags or flag
        try {
            windowManager.updateViewLayout(v, lp)
        } catch (e: Exception) {
            // view không còn gắn vào cửa sổ
        }
    }

    // ------------------------------------------------- phím tắt thoát hiểm

    /** Nhấn Vol+ và Vol− cùng lúc để tạm dừng / bật lại việc chặn cảm ứng. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> volUpHeld = down
            KeyEvent.KEYCODE_VOLUME_DOWN -> volDownHeld = down
            else -> return false
        }
        if (volUpHeld && volDownHeld) {
            if (!comboFired) {
                comboFired = true
                paused = !paused
                refresh()
                Toast.makeText(
                    this,
                    if (paused) R.string.toast_paused else R.string.toast_resumed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else if (!volUpHeld && !volDownHeld) {
            comboFired = false
        }
        return false // vẫn cho hệ thống chỉnh âm lượng bình thường
    }

    // ------------------------------------------------------- gửi cử chỉ

    private fun pressAt(x: Float, y: Float, durationMs: Long) {
        val w = overlay?.width ?: return
        val h = overlay?.height ?: return
        val path = Path().apply {
            moveTo(x.coerceIn(1f, (w - 1).toFloat()), y.coerceIn(1f, (h - 1).toFloat()))
        }
        dispatchPath(path, durationMs)
    }

    private fun swipeAlong(points: List<FloatArray>, durationMs: Long) {
        val w = overlay?.width ?: return
        val h = overlay?.height ?: return
        val pts = downsample(points, 40)
        if (pts.size < 2) return
        val path = Path()
        pts.forEachIndexed { i, p ->
            val x = p[0].coerceIn(1f, (w - 1).toFloat())
            val y = p[1].coerceIn(1f, (h - 1).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        dispatchPath(path, durationMs.coerceIn(120L, 500L))
    }

    /** Con lăn chuột → vuốt ngón tay. vScroll > 0 là lăn lên (nội dung đi xuống). */
    private fun scrollAt(x: Float, y: Float, vScroll: Float, hScroll: Float) {
        val w = overlay?.width ?: return
        val h = overlay?.height ?: return
        val unit = h * 0.16f
        val dy = (vScroll * unit).coerceIn(-h * 0.5f, h * 0.5f)
        val dx = (hScroll * unit).coerceIn(-w * 0.5f, w * 0.5f)
        if (hypot(dx, dy) < 1f) return

        val x0 = (x + dx / 2).coerceIn(8f, w - 8f)
        val x1 = (x - dx / 2).coerceIn(8f, w - 8f)
        val y0 = (y - dy / 2).coerceIn(8f, h - 8f)
        val y1 = (y + dy / 2).coerceIn(8f, h - 8f)
        if (hypot(x1 - x0, y1 - y0) < 1f) return

        val path = Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }
        dispatchPath(path, SCROLL_MS)
    }

    private fun dispatchPath(path: Path, durationMs: Long) {
        val stroke = try {
            GestureDescription.StrokeDescription(path, SETTLE_MS, max(1L, durationMs))
        } catch (e: IllegalArgumentException) {
            return
        }
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        // Cho cú bấm xuyên qua lớp phủ trong lúc cử chỉ đang chạy.
        inFlight++
        setTouchable(false)

        var released = false
        val release = Runnable {
            if (!released) {
                released = true
                inFlight = max(0, inFlight - 1)
                if (inFlight == 0) setTouchable(true)
            }
        }
        // Phòng hờ callback không bao giờ được gọi.
        handler.postDelayed(release, SETTLE_MS + durationMs + 2000L)

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.removeCallbacks(release)
                release.run()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.removeCallbacks(release)
                release.run()
            }
        }
        if (!dispatchGesture(gesture, callback, handler)) {
            handler.removeCallbacks(release)
            release.run()
        }
    }

    private fun statusBarHeightPx(): Float {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) {
            resources.getDimensionPixelSize(id).toFloat()
        } else {
            28f * resources.displayMetrics.density
        }
    }

    /**
     * Kéo chuột từ mép màn hình → hành động hệ thống (tức thì, không qua lớp phủ).
     * Trả về true nếu đã xử lý.
     */
    private fun handleEdgeGesture(
        downX: Float, downY: Float, upX: Float, upY: Float,
        heldMs: Long, w: Int, h: Int
    ): Boolean {
        val d = resources.displayMetrics.density
        val topZone = max(statusBarHeightPx(), 24f * d)
        val bottomZone = 28f * d
        val sideZone = 20f * d
        val minPull = 48f * d
        val dx = upX - downX
        val dy = upY - downY

        return when {
            // Kéo từ mép trên xuống: thông báo (40% bên phải: cài đặt nhanh)
            downY <= topZone && dy > minPull && dy > abs(dx) -> {
                performGlobalAction(
                    if (downX > w * 0.6f) GLOBAL_ACTION_QUICK_SETTINGS
                    else GLOBAL_ACTION_NOTIFICATIONS
                )
                true
            }
            // Kéo từ mép dưới lên: nhanh = Home, chậm/giữ = đa nhiệm
            downY >= h - bottomZone && -dy > minPull && -dy > abs(dx) -> {
                performGlobalAction(
                    if (heldMs >= 350L) GLOBAL_ACTION_RECENTS else GLOBAL_ACTION_HOME
                )
                true
            }
            // Kéo từ mép trái/phải vào trong: Quay lại
            (downX <= sideZone && dx > minPull && dx > abs(dy)) ||
                (downX >= w - sideZone && -dx > minPull && -dx > abs(dy)) -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                true
            }
            else -> false
        }
    }

    private fun downsample(src: List<FloatArray>, maxPoints: Int): List<FloatArray> {
        if (src.size <= maxPoints) return src.toList()
        val out = ArrayList<FloatArray>(maxPoints)
        val step = (src.size - 1).toFloat() / (maxPoints - 1)
        for (i in 0 until maxPoints) {
            out.add(src[(i * step).toInt().coerceAtMost(src.size - 1)])
        }
        out[maxPoints - 1] = src.last()
        return out
    }

    // ------------------------------------------------------- lớp phủ bắt sự kiện

    private inner class MouseCaptureView(context: Context) : View(context) {

        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var downButtons = 0
        private var moved = false
        private val trail = ArrayList<FloatArray>()

        override fun onTouchEvent(e: MotionEvent): Boolean {
            // Ngón tay / bút: nuốt hết, không làm gì.
            if (!isMouse(e)) return true

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    downTime = e.eventTime
                    downButtons =
                        if (e.buttonState != 0) e.buttonState else MotionEvent.BUTTON_PRIMARY
                    moved = false
                    trail.clear()
                    trail.add(floatArrayOf(downX, downY))
                }

                MotionEvent.ACTION_MOVE -> {
                    val x = e.rawX
                    val y = e.rawY
                    if (!moved && hypot(x - downX, y - downY) > slop) moved = true
                    if (moved) trail.add(floatArrayOf(x, y))
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) trail.add(floatArrayOf(e.rawX, e.rawY))
                    finishPress(e.eventTime - downTime)
                }

                MotionEvent.ACTION_CANCEL -> trail.clear()
            }
            return true
        }

        override fun onGenericMotionEvent(e: MotionEvent): Boolean {
            if (!isMouse(e)) return false
            if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
                scrollAt(
                    e.rawX,
                    e.rawY,
                    e.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                )
                return true
            }
            return false
        }

        private fun finishPress(heldMs: Long) {
            val buttons = downButtons
            val primary = (buttons and MotionEvent.BUTTON_PRIMARY) != 0
            val secondary = (buttons and MotionEvent.BUTTON_SECONDARY) != 0
            val tertiary = (buttons and MotionEvent.BUTTON_TERTIARY) != 0
            val sideOnly =
                (buttons and (MotionEvent.BUTTON_BACK or MotionEvent.BUTTON_FORWARD)) != 0 &&
                    !primary && !secondary && !tertiary

            when {
                sideOnly -> { /* phím cạnh chuột: hệ thống tự xử lý */ }
                moved && primary -> {
                    val last = trail.last()
                    val handled = handleEdgeGesture(
                        downX, downY, last[0], last[1], heldMs, width, height
                    )
                    if (!handled) swipeAlong(trail, heldMs)
                }
                moved -> { /* kéo bằng chuột phải/giữa: bỏ qua */ }
                secondary -> performGlobalAction(GLOBAL_ACTION_BACK)
                tertiary -> performGlobalAction(GLOBAL_ACTION_HOME)
                heldMs >= LONG_PRESS_HOLD_MS -> pressAt(downX, downY, LONG_PRESS_MS)
                else -> pressAt(downX, downY, TAP_MS)
            }
            trail.clear()
        }
    }
}

private fun isMouse(e: MotionEvent): Boolean =
    e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
        (e.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
