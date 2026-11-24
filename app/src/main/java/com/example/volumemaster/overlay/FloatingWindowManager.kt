package com.example.volumemaster.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.example.volumemaster.MainActivity
import com.example.volumemaster.R
import com.example.volumemaster.audio.VolumeManager
import com.example.volumemaster.databinding.LayoutFloatingBinding
import com.example.volumemaster.utils.PermissionManager
import com.example.volumemaster.utils.VolumePreferences
import com.example.volumemaster.utils.dp
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Edge controller (OPPO Smart Sidebar style): a slim native handle docked to the left/right screen
 * edge. Swiping the handle inward drags a WebView panel (HTML/CSS/JS from assets/volume-ui) out of
 * the edge, following the finger; releasing settles it open or closed. Tapping the handle toggles it
 * too, and swiping the panel back toward the edge collapses it. A vertical drag on the handle moves
 * it along the edge, and a long-press-drag moves it to the other edge. This class only owns the window,
 * dragging, snapping and animations; every volume change goes through [VolumeWebBridge] and
 * [VolumeManager] in Kotlin.
 *
 * The window is only as big as what is visible (handle when collapsed, panel + handle when
 * expanded), so it never swallows touches meant for the app underneath.
 */
class FloatingWindowManager(
    private val context: Context,
    private val volumeManager: VolumeManager,
    private val prefs: VolumePreferences
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_VolumeMaster_Overlay)
    private val geometry = ScreenGeometry(context, windowManager)
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var binding: LayoutFloatingBinding? = null
    private var params: WindowManager.LayoutParams? = null
    private var webView: WebView? = null
    private var pageReady = false
    private var interacting = false   // true while a finger is on the slider / +/- buttons
    private var webViewWarningShown = false

    private val bridgeHost = object : VolumeWebBridge.Host {
        override fun onCollapseRequested() { collapse() }
        override fun onCloseRequested() { prefs.floatingEnabled = false } // service removes the window
        override fun onOpenAppRequested() {
            collapse()
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open app from overlay", e)
            }
        }
        override fun onInteraction(active: Boolean) {
            interacting = active
            if (active) cancelAutoCollapse() else scheduleAutoCollapse()
        }
        override fun onNoSwipeZones(zones: List<RectF>) {
            binding?.panel?.noSwipeZones = zones
        }
    }
    private val bridge = VolumeWebBridge(context, volumeManager, prefs, bridgeHost)

    private var edge = VolumePreferences.EDGE_RIGHT
    private var orderedForEdge = -1
    private var handleBgEdge = -1

    private var expanded = false      // logical target state
    private var expansion = 0f        // animated 0 (collapsed) .. 1 (expanded)
    private var yPx = 0               // window top while collapsed
    private var expandedY = 0         // window top while expanded
    private var absolute = false      // true only while dragging / snapping (gravity START + absolute x)
    private var handleDragging = false
    private var swiping = false             // a finger is dragging the panel in/out right now
    private var swipeStartExpansion = 0f    // expansion (0..1) when that drag began

    private var expandAnimator: ValueAnimator? = null
    private var snapAnimator: ValueAnimator? = null

    private val autoCollapseRunnable = Runnable {
        if (interacting) scheduleAutoCollapse() else collapse()
    }

    // ---- sizes ----------------------------------------------------------------------------

    private val handleWidthPx get() = context.resources.getDimensionPixelSize(R.dimen.floating_handle_width)
    private val gapPx get() = context.dp(4)
    private val marginPx get() = context.dp(8)
    private fun handleHeightPx() = context.dp(prefs.buttonSizeDp.coerceIn(40, 96))

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun edgeGravity(e: Int): Int =
        Gravity.TOP or (if (e == VolumePreferences.EDGE_LEFT) Gravity.START else Gravity.END)

    // ---- vertical bounds (status bar / navigation bar / cutout aware) ------------------------

    private fun minY(): Int = geometry.insets(binding?.root).top + marginPx

    private fun maxY(windowHeight: Int): Int {
        val lo = minY()
        val hi = geometry.screenSize().y - geometry.insets(binding?.root).bottom - marginPx - windowHeight
        return max(lo, hi)
    }

    private fun yFromFraction(fraction: Float, windowHeight: Int): Int {
        val f = if (fraction < 0f) DEFAULT_Y_FRACTION else fraction.coerceIn(0f, 1f)
        val lo = minY()
        val hi = maxY(windowHeight)
        return (lo + f * (hi - lo)).roundToInt().coerceIn(lo, hi)
    }

    private fun fractionFromY(y: Int, windowHeight: Int): Float {
        val lo = minY()
        val hi = maxY(windowHeight)
        return if (hi <= lo) 0.5f else ((y - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
    }

    // ---- lifecycle --------------------------------------------------------------------------

    fun show() {
        if (binding != null) { // already showing: never create a second window
            applySettings()
            refresh()
            return
        }
        if (!PermissionManager.canDrawOverlays(context)) return

        val b = LayoutFloatingBinding.inflate(LayoutInflater.from(themedContext))
        edge = prefs.floatEdge
        yPx = yFromFraction(prefs.floatYFraction, handleHeightPx())

        val lp = WindowManager.LayoutParams(
            handleWidthPx,
            handleHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = edgeGravity(edge)
            x = 0
            y = yPx
        }

        try {
            windowManager.addView(b.root, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to add overlay window", e)
            return
        }
        binding = b
        params = lp
        expanded = false
        expansion = 0f
        absolute = false
        handleDragging = false
        orderedForEdge = -1
        handleBgEdge = -1

        b.panel.visibility = View.GONE
        applyPanelSize(b)
        attachWebView(b)
        setupInteraction(b)
        setupHandle(b)
        applyEdgeLayout()
        applyAppearance()
        refresh()

        if (prefs.startExpanded) b.root.post { if (binding === b) expand() }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        expandAnimator?.cancel()
        snapAnimator?.cancel()
        expandAnimator = null
        snapAnimator = null
        val b = binding
        if (b != null) {
            try { windowManager.removeViewImmediate(b.root) } catch (e: Exception) { Log.w(TAG, "removeView failed", e) }
        }
        destroyWebView() // after the window is gone: no WebView is left behind
        binding = null
        params = null
        interacting = false
        expanded = false
        expansion = 0f
        absolute = false
        handleDragging = false
        swiping = false
        orderedForEdge = -1
        handleBgEdge = -1
    }

    /** Rotation / size change: go back to the collapsed handle at the saved edge and height. */
    fun reposition() {
        if (binding == null) return
        resetToCollapsed()
    }

    /** Re-reads edge, size, opacity from preferences (called when a setting changes). */
    fun applySettings() {
        val b = binding ?: return
        val newEdge = prefs.floatEdge
        if (newEdge != edge) {
            edge = newEdge
            resetToCollapsed()
        }
        applyAppearance()
        if (!expanded && !handleDragging && !absolute) {
            yPx = yFromFraction(prefs.floatYFraction, handleHeightPx())
            finishCollapse()
        }
        if (b.root.isAttachedToWindow) refresh()
    }

    private fun resetToCollapsed() {
        val lp = params ?: return
        expandAnimator?.cancel()
        snapAnimator?.cancel()
        cancelAutoCollapse()
        expanded = false
        expansion = 0f
        absolute = false
        handleDragging = false
        swiping = false
        lp.gravity = edgeGravity(edge)
        lp.x = 0
        applyEdgeLayout()
        yPx = yFromFraction(prefs.floatYFraction, handleHeightPx())
        finishCollapse()
    }

    private fun updateLayout(b: LayoutFloatingBinding, lp: WindowManager.LayoutParams) {
        try { windowManager.updateViewLayout(b.root, lp) } catch (e: Exception) { Log.w(TAG, "updateViewLayout failed", e) }
    }

    // ---- appearance ---------------------------------------------------------------------------

    private fun applyEdgeLayout() {
        val b = binding ?: return
        if (orderedForEdge != edge) {
            b.root.removeView(b.panel)
            b.root.removeView(b.handle)
            if (edge == VolumePreferences.EDGE_RIGHT) {
                b.root.addView(b.panel)
                b.root.addView(b.handle)
            } else {
                b.root.addView(b.handle)
                b.root.addView(b.panel)
            }
            orderedForEdge = edge
        }
        val panelLp = b.panel.layoutParams as LinearLayout.LayoutParams
        panelLp.leftMargin = if (edge == VolumePreferences.EDGE_LEFT) gapPx else 0
        panelLp.rightMargin = if (edge == VolumePreferences.EDGE_RIGHT) gapPx else 0
        b.panel.layoutParams = panelLp
        b.panel.outwardSign = if (edge == VolumePreferences.EDGE_RIGHT) 1 else -1
        setHandleBackground(edge)
        pushEdge()
    }

    private fun setHandleBackground(forEdge: Int) {
        val b = binding ?: return
        if (handleBgEdge == forEdge) return
        handleBgEdge = forEdge
        b.handle.setBackgroundResource(
            if (forEdge == VolumePreferences.EDGE_RIGHT) R.drawable.bg_handle_right else R.drawable.bg_handle_left
        )
    }

    private fun applyAppearance() {
        val b = binding ?: return
        val w = handleWidthPx
        val h = handleHeightPx()
        val hp = b.handle.layoutParams
        if (hp.width != w || hp.height != h) {
            hp.width = w
            hp.height = h
            b.handle.layoutParams = hp
        }
        b.handle.alpha = prefs.opacityPercent.coerceIn(30, 100) / 100f
        pushAppearance() // the glass panel's own transparency is controlled by CSS
    }

    /** Cheap: updates the handle icon and, only while the panel is visible, pushes state to the page. */
    fun refresh() {
        val b = binding ?: return
        b.handleIcon.setImageResource(
            if (volumeManager.isMuted(prefs.selectedCategory)) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
        if (expanded || expansion > 0f) pushState()
    }

    // ---- WebView ------------------------------------------------------------------------------------

    private fun attachWebView(b: LayoutFloatingBinding) {
        pageReady = false
        val wv = createWebView()
        webView = wv
        if (wv == null) {
            if (!webViewWarningShown) {
                webViewWarningShown = true
                Toast.makeText(context, R.string.msg_webview_unavailable, Toast.LENGTH_LONG).show()
            }
            return
        }
        b.webContainer.addView(
            wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    /** Created once per overlay session and reused for every expand/collapse and volume change. */
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun createWebView(): WebView? = try {
        val wv = WebView(themedContext)
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.isLongClickable = false
        wv.setOnLongClickListener { true }
        wv.isHapticFeedbackEnabled = false

        val s = wv.settings
        s.javaScriptEnabled = true               // required for the UI; the page is our own asset
        s.allowFileAccess = false                // assets are still reachable via file:///android_asset
        s.allowContentAccess = false
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false
        s.domStorageEnabled = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.setGeolocationEnabled(false)
        s.blockNetworkLoads = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100                         // system font scale must not break the fixed layout

        wv.webViewClient = OverlayWebViewClient()
        wv.addJavascriptInterface(bridge, BRIDGE_NAME)
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect, debug builds only
        }
        wv.loadUrl(PAGE_URL)
        wv
    } catch (e: Exception) {
        Log.e(TAG, "WebView could not be created", e)
        null
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        pageReady = false
        try {
            wv.stopLoading()
            wv.removeJavascriptInterface(BRIDGE_NAME)
            wv.webViewClient = WebViewClient()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "WebView cleanup failed", e)
        }
    }

    private fun runJs(script: String) {
        val wv = webView ?: return
        if (!pageReady) return
        wv.evaluateJavascript(script, null)
    }

    /** Kotlin -> page. The page only renders this; it never answers with a volume change. */
    private fun pushState() = runJs("window.VolumeUI&&window.VolumeUI.update(${bridge.snapshot()})")

    private fun pushAppearance() {
        val alpha = prefs.opacityPercent.coerceIn(30, 100) / 100f
        runJs("window.VolumeUI&&window.VolumeUI.setOpacity($alpha)")
    }

    private fun pushEdge() {
        val name = if (edge == VolumePreferences.EDGE_LEFT) "left" else "right"
        runJs("window.VolumeUI&&window.VolumeUI.setEdge('$name')")
    }

    /** Only our own bundled page may load; everything else is refused. */
    private inner class OverlayWebViewClient : WebViewClient() {
        @Deprecated("Legacy callback for API < 24")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            if (url.startsWith(ASSET_PREFIX)) return null
            Log.w(TAG, "Blocked WebView request: $url")
            return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            pageReady = true
            pushAppearance()
            pushEdge()
            pushState()
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            Log.w(TAG, "WebView render process gone; rebuilding")
            handler.post {
                val b = binding ?: return@post
                destroyWebView()
                attachWebView(b)
            }
            return true
        }
    }

    // ---- touch plumbing --------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInteraction(b: LayoutFloatingBinding) {
        b.panel.onInteraction = { scheduleAutoCollapse() }
        b.panel.swipeBlocked = { interacting }
        // Swipe the open panel back toward its edge to collapse it (never from slider / +/- buttons).
        b.panel.swipeListener = object : InteractionLinearLayout.SwipeListener {
            override fun onSwipeStart() = startSwipe()
            override fun onSwipeMove(dxRaw: Float) = updateSwipe(dxRaw * inwardSign())
            override fun onSwipeEnd(dxRaw: Float, velocityXRaw: Float) =
                endSwipe(dxRaw * inwardSign(), velocityXRaw * inwardSign())
        }
        // Tap anywhere outside the expanded panel collapses it.
        b.root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && expanded && !swiping) {
                collapse()
                true
            } else false
        }
    }

    /** Fits the fixed-size panel to the current screen (rotation, small displays, system bars). */
    private fun applyPanelSize(b: LayoutFloatingBinding) {
        val screen = geometry.screenSize()
        val insets = geometry.insets(b.root)
        val availH = screen.y - insets.top - insets.bottom - 2 * marginPx
        val availW = screen.x - handleWidthPx - gapPx - marginPx
        val w = min(context.resources.getDimensionPixelSize(R.dimen.floating_panel_width), max(availW, context.dp(160)))
        val h = min(context.resources.getDimensionPixelSize(R.dimen.floating_panel_height), max(availH, context.dp(200)))
        val lp = b.panel.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            b.panel.layoutParams = lp
        }
    }

    // ---- handle: swipe inward to expand, tap to toggle, drag along / between edges -------------

    private enum class HandleMode { NONE, SWIPE, SLIDE_Y, FREE_MOVE, IGNORE }

    /** +1 when moving toward screen-right is "inward" (left edge), -1 for the right edge. */
    private fun inwardSign(): Float = if (edge == VolumePreferences.EDGE_RIGHT) -1f else 1f

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHandle(b: LayoutFloatingBinding) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var mode = HandleMode.NONE
        var tracker: VelocityTracker? = null

        // Ask the system not to treat a swipe on the handle as the Back gesture (best effort: some
        // OEMs ignore exclusion rects for overlay windows).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.handle.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                v.systemGestureExclusionRects = listOf(Rect(0, 0, right - left, bottom - top))
            }
        }

        fun trackRaw(e: MotionEvent) {
            val t = tracker ?: return
            val adjusted = MotionEvent.obtain(e)
            adjusted.offsetLocation(e.rawX - e.x, e.rawY - e.y)
            t.addMovement(adjusted)
            adjusted.recycle()
        }

        fun commitSlideY() {
            val lp = params ?: return
            yPx = lp.y
            prefs.floatYFraction = fractionFromY(yPx, lp.height)
        }

        // Press and hold, then drag: free move, so the handle can still be moved to the other edge.
        val longPress = Runnable {
            val lp = params ?: return@Runnable
            if (mode != HandleMode.NONE || expanded || expansion > 0f || swiping) return@Runnable
            mode = HandleMode.FREE_MOVE
            handleDragging = true
            b.handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val screenW = geometry.screenSize().x
            startX = if (edge == VolumePreferences.EDGE_LEFT) 0 else screenW - handleWidthPx
            startY = lp.y
            absolute = true
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = startX
            updateLayout(b, lp)
        }

        fun finishGesture() {
            handler.removeCallbacks(longPress)
            tracker?.recycle()
            tracker = null
            mode = HandleMode.NONE
        }

        b.handle.setOnClickListener { toggle() }
        b.handle.setOnTouchListener { v, e ->
            val lp = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelAutoCollapse()
                    snapAnimator?.end() // finish a running snap so positions are consistent
                    downX = e.rawX
                    downY = e.rawY
                    mode = HandleMode.NONE
                    handleDragging = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain()
                    trackRaw(e)
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    trackRaw(e)
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (mode == HandleMode.NONE && hypot(dx, dy) > touchSlop) {
                        handler.removeCallbacks(longPress)
                        val revealed = expanded || expansion > 0f
                        val inward = dx * inwardSign()
                        mode = when {
                            // Horizontal: inward drags open the panel, drags toward the edge close it.
                            abs(dx) > abs(dy) ->
                                if (!revealed && inward < 0f) HandleMode.IGNORE else HandleMode.SWIPE
                            // Vertical: slide along the edge (only while collapsed).
                            revealed -> HandleMode.IGNORE
                            else -> HandleMode.SLIDE_Y
                        }
                        when (mode) {
                            HandleMode.SWIPE -> startSwipe()
                            HandleMode.SLIDE_Y -> startY = lp.y
                            else -> Unit
                        }
                    }
                    when (mode) {
                        HandleMode.SWIPE -> updateSwipe(dx * inwardSign())
                        HandleMode.SLIDE_Y -> {
                            lp.y = (startY + dy).roundToInt().coerceIn(minY(), maxY(lp.height))
                            updateLayout(b, lp)
                        }
                        HandleMode.FREE_MOVE -> {
                            val screenW = geometry.screenSize().x
                            val w = handleWidthPx
                            lp.x = (startX + dx).roundToInt().coerceIn(0, max(0, screenW - w))
                            lp.y = (startY + dy).roundToInt().coerceIn(minY(), maxY(lp.height))
                            setHandleBackground(
                                if (lp.x + w / 2 < screenW / 2) VolumePreferences.EDGE_LEFT else VolumePreferences.EDGE_RIGHT
                            )
                            updateLayout(b, lp)
                        }
                        else -> Unit
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    trackRaw(e)
                    val dx = e.rawX - downX
                    when (mode) {
                        HandleMode.NONE -> { handler.removeCallbacks(longPress); v.performClick() }
                        HandleMode.SWIPE -> {
                            tracker?.computeCurrentVelocity(1000)
                            endSwipe(dx * inwardSign(), (tracker?.xVelocity ?: 0f) * inwardSign())
                        }
                        HandleMode.SLIDE_Y -> { commitSlideY(); scheduleAutoCollapse() }
                        HandleMode.FREE_MOVE -> { handleDragging = false; snapToEdge() }
                        HandleMode.IGNORE -> scheduleAutoCollapse()
                    }
                    finishGesture()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    when (mode) {
                        HandleMode.SWIPE -> endSwipe((e.rawX - downX) * inwardSign(), 0f)
                        HandleMode.SLIDE_Y -> commitSlideY()
                        HandleMode.FREE_MOVE -> { handleDragging = false; snapToEdge() }
                        else -> scheduleAutoCollapse()
                    }
                    finishGesture()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val b = binding ?: return
        val lp = params ?: return
        val screenW = geometry.screenSize().x
        val w = handleWidthPx
        val newEdge = if (lp.x + w / 2 < screenW / 2) VolumePreferences.EDGE_LEFT else VolumePreferences.EDGE_RIGHT
        val targetX = if (newEdge == VolumePreferences.EDGE_LEFT) 0 else screenW - w

        snapAnimator?.cancel()
        val anim = ValueAnimator.ofInt(lp.x, targetX)
        anim.duration = 180L
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener {
            lp.x = it.animatedValue as Int
            updateLayout(b, lp)
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled && binding === b) finishSnap(newEdge)
            }
        })
        snapAnimator = anim
        anim.start()
    }

    private fun finishSnap(newEdge: Int) {
        val b = binding ?: return
        val lp = params ?: return
        edge = newEdge
        absolute = false
        lp.gravity = edgeGravity(edge)
        lp.x = 0
        yPx = lp.y
        applyEdgeLayout()
        updateLayout(b, lp)
        // Persist (edge first is fine: applySettings sees edge == prefs and does nothing).
        prefs.floatYFraction = fractionFromY(yPx, lp.height)
        prefs.floatEdge = edge
    }

    // ---- expand / collapse ------------------------------------------------------------------------

    private fun toggle() { if (expanded) collapse() else expand() }

    private fun expand() {
        val b = binding ?: return
        val lp = params ?: return
        if (expanded) { scheduleAutoCollapse(); return }
        snapAnimator?.end()
        expanded = true
        refresh()
        prepareReveal(b, lp)
        runExpansion(true)
    }

    /**
     * Grows the window from "just the handle" to "panel + handle" with the panel still hidden at the
     * edge. Shared by tap-to-expand and swipe-to-expand. The one WebView is reused, never recreated.
     */
    private fun prepareReveal(b: LayoutFloatingBinding, lp: WindowManager.LayoutParams) {
        applyPanelSize(b)
        val windowHeight = max(b.panel.layoutParams.height, handleHeightPx())
        expandedY = yPx.coerceIn(minY(), maxY(windowHeight))

        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        webView?.onResume()
        pushState()
        pushAppearance()
        b.panel.visibility = View.VISIBLE
        applyProgress(b, lp, expansion)
        updateLayout(b, lp)
    }

    // ---- swipe: the panel follows the finger, release settles it ------------------------------

    /** Distance the panel travels between fully collapsed and fully expanded. */
    private fun swipeTravel(b: LayoutFloatingBinding): Float =
        max(1, b.panel.layoutParams.width + gapPx).toFloat()

    private fun startSwipe() {
        val b = binding ?: return
        val lp = params ?: return
        swiping = true
        cancelAutoCollapse()
        expandAnimator?.cancel() // a finger grabbing a moving panel takes over from the animation
        snapAnimator?.end()
        swipeStartExpansion = expansion
        if (b.panel.visibility != View.VISIBLE) prepareReveal(b, lp)
    }

    /** [inward]: pixels moved toward the screen centre since the finger went down (negative = toward the edge). */
    private fun updateSwipe(inward: Float) {
        if (!swiping) return
        val b = binding ?: return
        val lp = params ?: return
        val t = (swipeStartExpansion + inward / swipeTravel(b)).coerceIn(0f, 1f)
        applyProgress(b, lp, t)
    }

    private fun endSwipe(inward: Float, inwardVelocity: Float) {
        if (!swiping) return
        swiping = false
        if (binding == null) return
        val threshold = prefs.swipeThresholdPercent / 100f
        val fling = context.dp(FLING_DP).toFloat()
        val t = expansion
        val open = when {
            inwardVelocity > fling -> true                  // flicked inward
            inwardVelocity < -fling -> false                // flicked toward the edge
            swipeStartExpansion < 0.5f -> t >= threshold    // was closed: dragged far enough to open?
            else -> t > 1f - threshold                      // was open: not dragged far enough to close
        }
        expanded = open
        if (!open) cancelAutoCollapse()
        runExpansion(open, decelerate = true) // ease out of the finger's momentum
    }

    fun collapse() {
        if (!expanded || swiping) return
        expanded = false
        cancelAutoCollapse()
        runExpansion(false)
    }

    private fun runExpansion(show: Boolean, decelerate: Boolean = show) {
        val b = binding ?: return
        val lp = params ?: return
        expandAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(expansion, if (show) 1f else 0f)
        val remaining = abs((if (show) 1f else 0f) - expansion).coerceIn(0.35f, 1f)
        anim.duration = ((if (show) 240L else 190L) * remaining).toLong()
        anim.interpolator = if (decelerate) DecelerateInterpolator() else AccelerateInterpolator()
        anim.addUpdateListener { applyProgress(b, lp, it.animatedValue as Float) }
        anim.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled || binding !== b) return
                if (show) scheduleAutoCollapse() else finishCollapse()
            }
        })
        expandAnimator = anim
        anim.start()
    }

    /** t = 0 collapsed, 1 expanded: slides the panel in from the docked edge. */
    private fun applyProgress(b: LayoutFloatingBinding, lp: WindowManager.LayoutParams, t: Float) {
        expansion = t
        val dir = if (edge == VolumePreferences.EDGE_RIGHT) 1f else -1f
        b.panel.translationX = dir * (b.panel.layoutParams.width + gapPx) * (1f - t)
        b.panel.alpha = t
        if (expandedY != yPx) {
            val ny = (yPx + (expandedY - yPx) * t).roundToInt()
            if (ny != lp.y) {
                lp.y = ny
                updateLayout(b, lp)
            }
        }
    }

    /** Shrinks the window back to just the handle (also used for instant resets). */
    private fun finishCollapse() {
        val b = binding ?: return
        val lp = params ?: return
        expansion = 0f
        b.panel.visibility = View.GONE
        webView?.onPause()
        b.panel.translationX = 0f
        b.panel.alpha = 1f
        lp.width = handleWidthPx
        lp.height = handleHeightPx()
        lp.y = yPx
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        updateLayout(b, lp)
    }

    private fun scheduleAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable)
        val seconds = prefs.autoCollapseSeconds
        if (expanded && seconds > 0) handler.postDelayed(autoCollapseRunnable, seconds * 1000L)
    }

    private fun cancelAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable)
    }

    private companion object {
        const val TAG = "FloatingWindow"
        const val BRIDGE_NAME = "VolumeBridge"
        const val ASSET_PREFIX = "file:///android_asset/volume-ui/"
        const val PAGE_URL = ASSET_PREFIX + "index.html"
        const val DEFAULT_Y_FRACTION = 0.35f
        const val FLING_DP = 500 // inward/outward speed (dp/s) that decides a swipe regardless of distance
    }
}
