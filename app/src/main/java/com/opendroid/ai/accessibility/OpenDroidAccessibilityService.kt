package com.opendroid.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.opendroid.ai.R
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.service.OpenDroidService
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.os.Build
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@AndroidEntryPoint
class OpenDroidAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var agentLoop: AgentLoop

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var floatingView: FloatingWidgetView? = null
    private var isButtonAdded = false
    private var isDeviceLocked = false
    private var showFloatingButtonSetting = false

    /**
     * BroadcastReceiver that tracks device lock/unlock state.
     * Hides the floating button when the device is locked to prevent
     * unintended interaction from the lock screen.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isDeviceLocked = true
                    refreshFloatingButtonVisibility()
                }
                Intent.ACTION_USER_PRESENT -> {
                    // ACTION_USER_PRESENT is broadcast when the user unlocks the device
                    isDeviceLocked = false
                    refreshFloatingButtonVisibility()
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Screen turned on but may still be locked; check KeyguardManager
                    val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    isDeviceLocked = keyguardManager?.isKeyguardLocked == true
                    refreshFloatingButtonVisibility()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Initialize lock state
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        isDeviceLocked = keyguardManager?.isKeyguardLocked == true

        // Register receiver for screen on/off/unlock events
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, filter)

        serviceScope.launch {
            settingsRepository.llmConfig
                .map { it.showFloatingButton }
                .collectLatest { show ->
                    showFloatingButtonSetting = show
                    refreshFloatingButtonVisibility()
                }
        }

        serviceScope.launch {
            agentLoop.agentState.collectLatest { state ->
                floatingView?.updateState(state)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handle events if needed, e.g. window content updates
    }

    override fun onInterrupt() {
        // Handle interruption
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            // Receiver may not have been registered
        }
        serviceScope.cancel()
        removeFloatingButton()
        instance = null
    }

    /**
     * Refreshes the floating button visibility based on both the user setting
     * and the device lock state. The button is only shown when the setting is
     * enabled AND the device is unlocked.
     */
    private fun refreshFloatingButtonVisibility() {
        if (showFloatingButtonSetting && !isDeviceLocked) {
            addFloatingButton()
        } else {
            removeFloatingButton()
        }
    }

    private fun addFloatingButton() {
        if (isButtonAdded) return

        if (windowManager == null) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }

        val view = FloatingWidgetView(this)
        view.updateState(agentLoop.agentState.value)
        floatingView = view

        val params = WindowManager.LayoutParams(
            dpToPx(64),
            dpToPx(64),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false
            private val touchSlop = 10f
            private val longPressRunnable = Runnable {
                isClick = false
                triggerMicrophoneAction()
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        view.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                            if (isClick) {
                                isClick = false
                                view.removeCallbacks(longPressRunnable)
                            }
                        }
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()

                        val displayMetrics = resources.displayMetrics
                        val screenWidth = displayMetrics.widthPixels
                        val screenHeight = displayMetrics.heightPixels
                        params.x = params.x.coerceIn(0, screenWidth - params.width)
                        params.y = params.y.coerceIn(0, screenHeight - params.height)

                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            // View might have been removed
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        view.removeCallbacks(longPressRunnable)
                        if (isClick) {
                            openMainActivityAction()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        view.removeCallbacks(longPressRunnable)
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(view, params)
            isButtonAdded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeFloatingButton() {
        if (!isButtonAdded) return
        try {
            floatingView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            floatingView = null
            isButtonAdded = false
        }
    }

    private fun triggerMicrophoneAction() {
        val intent = Intent(this, OpenDroidService::class.java).apply {
            action = OpenDroidService.ACTION_TRIGGER_RECORD
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openMainActivityAction() {
        val intent = Intent(this, com.opendroid.ai.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    private fun dpToPx(dp: Int): Int {
        return Math.round(dp * resources.displayMetrics.density)
    }

    inner class FloatingWidgetView(context: Context) : android.widget.ImageView(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pulseRadius = 0f
        private var pulseAlpha = 255
        private var state: AgentState = AgentState.Idle

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                pulseRadius = value * dpToPx(8f)
                pulseAlpha = ((1f - value) * 150).toInt()
                invalidate()
            }
        }

        init {
            setImageResource(R.drawable.bot)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val padding = dpToPx(12)
            setPadding(padding, padding, padding, padding)
        }

        fun updateState(newState: AgentState) {
            if (this.state == newState) return
            this.state = newState

            animator.cancel()
            when (newState) {
                is AgentState.Thinking -> {
                    animator.duration = 600
                    animator.repeatMode = ValueAnimator.RESTART
                }
                is AgentState.Listening -> {
                    animator.duration = 1000
                    animator.repeatMode = ValueAnimator.REVERSE
                }
                is AgentState.Speaking -> {
                    animator.duration = 800
                    animator.repeatMode = ValueAnimator.REVERSE
                }
                else -> {
                    animator.duration = 2000
                    animator.repeatMode = ValueAnimator.REVERSE
                }
            }
            if (isAttachedToWindow) {
                animator.start()
            }
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animator.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = (width / 2f) - dpToPx(8f)

            // Draw cyber grey background circle
            paint.color = Color.parseColor("#121216")
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, radius, paint)

            // Draw center logo
            super.onDraw(canvas)

            // Draw glow border
            val color = when (state) {
                is AgentState.Idle -> Color.parseColor("#00FF66") // Neon green
                is AgentState.Listening -> Color.parseColor("#FF3B30") // Pulsing red
                is AgentState.Thinking -> Color.parseColor("#00F0FF") // Cyan
                is AgentState.Speaking -> Color.parseColor("#007AFF") // Neon blue
                is AgentState.ExecutingPlan -> Color.parseColor("#00FFCC") // Cyan-green
                else -> Color.parseColor("#00FF66")
            }

            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpToPx(3f)

            // Draw main border ring
            canvas.drawCircle(cx, cy, radius, paint)

            // Draw glowing pulse ring
            glowPaint.color = color
            glowPaint.style = Paint.Style.STROKE

            if (state is AgentState.Listening || state is AgentState.Thinking || state is AgentState.Speaking) {
                glowPaint.strokeWidth = dpToPx(1.5f)
                glowPaint.alpha = pulseAlpha
                canvas.drawCircle(cx, cy, radius + pulseRadius, glowPaint)
            }
        }
    }

    // --- Node Automation Methods ---

    fun findAndClick(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                return true
            }
            // Try parent node if the leaf is not clickable
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    node.recycle()
                    return true
                }
                parent = parent.parent
            }
            node.recycle()
        }
        return false
    }

    fun findAndClickById(viewId: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                return true
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    node.recycle()
                    return true
                }
                parent = parent.parent
            }
            node.recycle()
        }
        return false
    }

    fun findAndType(searchText: String, content: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(searchText)
        for (node in nodes) {
            if (node.isEditable) {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                node.recycle()
                return true
            }
            node.recycle()
        }
        return false
    }

    fun findAndTypeById(viewId: String, content: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            if (node.isEditable) {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                node.recycle()
                return true
            }
            node.recycle()
        }
        return false
    }

    fun performScroll(forward: Boolean): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val success = performScrollOnNode(rootNode, action)
        rootNode.recycle()
        return success
    }

    private fun performScrollOnNode(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable) {
            return node.performAction(action)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (performScrollOnNode(child, action)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    // --- Gesture Automation Methods (Coordinate Taps) ---

    fun clickCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().apply {
            addStroke(stroke)
        }.build()

        return dispatchGesture(gesture, null, null)
    }

    // --- Screen Text Extraction ---

    fun getScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        extractTextFromNode(rootNode, sb)
        rootNode.recycle()
        return sb.toString()
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val nodeText = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        
        if (!nodeText.isNullOrEmpty()) {
            sb.append(nodeText).append("\n")
        } else if (!contentDesc.isNullOrEmpty()) {
            sb.append(contentDesc).append("\n")
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractTextFromNode(child, sb)
            child.recycle()
        }
    }

    suspend fun takeScreenshotAndEncode(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return suspendCoroutine { continuation ->
            try {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                if (bitmap == null) {
                                    continuation.resume(null)
                                    return
                                }
                                val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                bitmap.recycle()
                                hardwareBuffer.close()

                                if (softwareBitmap == null) {
                                    continuation.resume(null)
                                    return
                                }

                                val outputStream = ByteArrayOutputStream()
                                softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                                val byteArray = outputStream.toByteArray()
                                val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
                                softwareBitmap.recycle()
                                continuation.resume(base64String)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                continuation.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            continuation.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
                continuation.resume(null)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: OpenDroidAccessibilityService? = null

        fun getInstance(): OpenDroidAccessibilityService? = instance
    }
}
