package com.mja.reyamf.xposed.ui.window

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.ITaskStackListenerProxy
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.DISPLAY_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.IPackageManagerHidden
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.IRotationWatcher
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManagerHidden
import android.widget.ImageButton
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.flingAnimationOf
import androidx.wear.widget.RoundedDrawable
import com.mja.reyamf.common.reflect.argTypes
import com.mja.reyamf.common.reflect.args
import com.mja.reyamf.common.reflect.getObject
import com.mja.reyamf.common.reflect.getObjectAs
import com.mja.reyamf.common.reflect.invokeMethod
import com.google.android.material.color.MaterialColors
import com.mja.reyamf.common.WindowMetrics
import com.mja.reyamf.common.getAttr
import com.mja.reyamf.common.onException
import com.mja.reyamf.common.runMain
import com.mja.reyamf.databinding.LeftBackGestureOverlayBinding
import com.mja.reyamf.databinding.RightBackGestureOverlayBinding
import com.mja.reyamf.databinding.WindowActionsBubbleBinding
import com.mja.reyamf.databinding.WindowAppBinding
import kotlinx.coroutines.withContext
import com.mja.reyamf.xposed.services.YAMFManager
import com.mja.reyamf.xposed.services.YAMFManager.config
import com.mja.reyamf.xposed.utils.Instances
import com.mja.reyamf.xposed.utils.RunMainThreadQueue
import com.mja.reyamf.xposed.utils.TipUtil
import com.mja.reyamf.xposed.utils.animateAlpha
import com.mja.reyamf.xposed.utils.animateResize
import com.mja.reyamf.xposed.utils.animateScaleThenResize
import com.mja.reyamf.xposed.utils.dpToPx
import com.mja.reyamf.xposed.utils.getActivityInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sign
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable


@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class AppWindow(
    val context: Context,
    private val flags: Int,
    private val onVirtualDisplayCreated: (Int) -> Unit
) :
    TextureView.SurfaceTextureListener, SurfaceHolder.Callback {
    companion object {
        const val TAG = "reYAMF_AppWindow"
        const val ACTION_RESET_ALL_WINDOW = "com.mja.reyamf.ui.window.action.ACTION_RESET_ALL_WINDOW"
    }

    lateinit var binding: WindowAppBinding
    lateinit var bindingLeftBackGesture: LeftBackGestureOverlayBinding
    lateinit var bindingRightBackGesture: RightBackGestureOverlayBinding
    private lateinit var virtualDisplay: VirtualDisplay
    private val taskStackListener =
            // The callback can arrive on a binder thread and the argument list differs between
            // Android releases, so both are checked and the work is re-dispatched to main.
            val taskInfo = args.firstOrNull() as? ActivityManager.RunningTaskInfo
        ITaskStackListenerProxy.newInstance(context.classLoader) { args, method ->
            when (method.name) {
                "onTaskMovedToFront" -> if (taskInfo != null) {
                    runMain { onTaskMovedToFront(taskInfo) }
                }

                "onTaskDescriptionChanged" -> if (taskInfo != null) {
                    runMain { onTaskDescriptionChanged(taskInfo) }

                "onTaskRemovalStarted" -> runMain { onDestroy() }
                }
            }
        }
    private val rotationWatcher = RotationWatcher()
    private val surfaceOnTouchListener = SurfaceOnTouchListener()
    private val surfaceOnGenericMotionListener = SurfaceOnGenericMotionListener()
    var displayId = -1
    var rotateLock = false
    var isMini = false
    var isCollapsed = false
    private var halfWidth = 0
    private var halfHeight = 0
    lateinit var surfaceView: View
    // Computed in doInit(); `reduceDPI` is applied exactly once, by densityFor().
    private var newDpi = WindowMetrics.FALLBACK_DENSITY
    private var originalWidth: Int = 0
    private var originalHeight: Int = 0
    private var isResize: Boolean = true
    private var orientation = 0
    private var params = WindowManager.LayoutParams()
    private var paramsBg = WindowManager.LayoutParams()
    private var backGestureJob: Job? = null
    private var lastClickTime = 0L
    private val DOUBLE_CLICK_TIME_DELTA: Long = 300
    /** Which control started the gesture, so the shared gesture callbacks know what was tapped. */
    private enum class TapSource { ROOT, NOTCH }
    private var tapSource = TapSource.ROOT
    private lateinit var bubble: WindowActionsBubbleBinding
    private var bubbleWidth = 0
    private var bubbleHeight = 0
    // The bubble lives in its own overlay window: inside the chrome it would be clipped by the
    // window surface and could not float above the window's top edge.
    private val bubbleParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
    private var isSuperShown = false

    private val broadcastReceiver = object : BroadcastReceiver() {
            if (isDestroyed) return
            // Reposition-only by design: the window and its virtual display stay alive, so this
            // path allocates and releases nothing. Teardown (display release included) lives in
            // onDestroy(), which only the Close shortcut and the bar double click reach.
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESET_ALL_WINDOW) {
                val lp = binding.root.layoutParams as WindowManager.LayoutParams
                lp.apply {
                    x = 0
                    y = 0
                }
                positionBubble()
                Instances.windowManager.updateViewLayout(binding.root, lp)
                val width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200F, context.resources.displayMetrics).toInt()
                val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 300F, context.resources.displayMetrics).toInt()
                binding.vSizePreviewer.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
                surfaceView.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
            }
        }
    }

    init {
        runCatching {
            bubble = WindowActionsBubbleBinding.inflate(LayoutInflater.from(context))
            bubble.root.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            bubbleWidth = bubble.root.measuredWidth
            bubbleHeight = bubble.root.measuredHeight
            bubbleParams.width = bubbleWidth
            bubbleParams.height = bubbleHeight
            binding = WindowAppBinding.inflate(LayoutInflater.from(context))
            bindingLeftBackGesture = LeftBackGestureOverlayBinding.inflate(LayoutInflater.from(context))
            bindingRightBackGesture = RightBackGestureOverlayBinding.inflate(LayoutInflater.from(context))
        }.onException { e ->
            Log.e(TAG, "Failed to create new window, did you reboot?", e)
            TipUtil.showToast("Failed to create new window, did you reboot?")
        }.onSuccess {
            doInit()
        }
    }

    private fun doInit() {
        when(config.surfaceView) {
            0 -> {
                surfaceView = binding.viewSurface
                binding.viewTexture.visibility = View.GONE
            }
            1 -> {
                surfaceView = binding.viewTexture
                binding.viewSurface.visibility = View.GONE
            }
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        )

        val displayManager = context.getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0
        params.y = when (rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                orientation = 0
                config.portraitY
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                orientation = 1
                config.landscapeY
            }
            else -> {
                surfaceView = binding.viewTexture
                binding.viewSurface.visibility = View.GONE
            }
            else -> 0
        }

        params.apply {
            if (orientation == 0) {
                gravity = Gravity.CENTER
                y = -80.dpToPx().toInt()
            } else {
                gravity = Gravity.TOP or Gravity.START
                y = 0
            }
            x = 0
//            this as WindowLayoutParamsHidden
//            privateFlags = privateFlags or WindowLayoutParamsHidden.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
        }

        paramsBg = WindowManager.LayoutParams(
            20.dpToPx().toInt(),
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        paramsBg.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        bindingLeftBackGesture.root.let {
            paramsBg.gravity = Gravity.START or Gravity.TOP
            Instances.windowManager.addView(bindingLeftBackGesture.root, paramsBg)
        }

        bindingRightBackGesture.root.let {
            val paramsBgR = WindowManager.LayoutParams(
                20.dpToPx().toInt(),
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            paramsBgR.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            paramsBgR.gravity = Gravity.END or Gravity.TOP
            Instances.windowManager.addView(bindingRightBackGesture.root, paramsBgR)
        }

        binding.root.let { layout ->
            Instances.windowManager.addView(layout, params)
        }

        binding.rootClickMask.setOnTouchListener { _, event ->
            moveGestureDetector.onTouchEvent(event)
            moveToTopIfNeed(event)
            true
        }

        binding.cvBarClickMask.setOnClickListener {
            val clickTime = System.currentTimeMillis()
            if (clickTime - lastClickTime < DOUBLE_CLICK_TIME_DELTA) {
                isResize = false
                backGestureJob?.cancel()
                backGestureJob = null

                binding.cvappIcon.visibility = View.INVISIBLE
                if (orientation == 0) {
                    animateAlpha(binding.rlBarControllerBottom, 1f, 0f) {
                        binding.rlBarControllerBottom.visibility = View.GONE
                    }
                } else {
                    animateAlpha(binding.rlBarControllerSide, 1f, 0f) {
                        binding.rlBarControllerSide.visibility = View.GONE
                    }
                }

                CoroutineScope(Dispatchers.IO).launch {
                    delay(200)

                    withContext(Dispatchers.Main) {
        bubble.root.visibility = View.GONE
        runCatching {
            Instances.windowManager.addView(bubble.root, bubbleParams)
            bubble.root.post { positionBubble() }
        }.onFailure { Log.e(TAG, "cannot add the action bubble", it) }
        // Resizes, rotations and the collapse animations all land here, so the bubble follows
        // the window's new top edge without every code path having to remember it.
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionBubble() }

                        animateScaleThenResize(
            tapSource = TapSource.ROOT
                            binding.cvParent,
                            1F, 1F,
                            0F, 0F,
                            0.5F, 0.5F,
                            0, 0,
                            context
                        ) {
                            onDestroy()
                        }
                    }
                }
            }
            lastClickTime = clickTime
        }

        binding.cvBarSideClickMask.setOnClickListener {
            val clickTime = System.currentTimeMillis()
            if (clickTime - lastClickTime < DOUBLE_CLICK_TIME_DELTA) {
                isResize = false
                backGestureJob?.cancel()
                backGestureJob = null

                binding.cvappIcon.visibility = View.INVISIBLE
                if (orientation == 0) {
                    animateAlpha(binding.rlBarControllerBottom, 1f, 0f) {
                        binding.rlBarControllerBottom.visibility = View.GONE
                    }
                } else {
                    animateAlpha(binding.rlBarControllerSide, 1f, 0f) {
                        binding.rlBarControllerSide.visibility = View.GONE
                    }
                }

                CoroutineScope(Dispatchers.IO).launch {
                    delay(200)

                    withContext(Dispatchers.Main) {
                        animateScaleThenResize(
                            binding.cvParent,
                            1F, 1F,
                            0F, 0F,
                            0.5F, 0.5F,
                            0, 0,
                            context
                        ) {
                            onDestroy()
                        }
                    }
                }
            }
            lastClickTime = clickTime
        }

        binding.ibSuper.setOnClickListener {
            isSuperShown = true
            animateAlpha(binding.clSuperLayout, 0f, 1f)
        }

        binding.cvBarClickMask.setOnTouchListener { _, event ->
            moveGestureDetector.onTouchEvent(event)
            moveToTopIfNeed(event)
            true
        }

        edgeResize(binding.ibResizeLeft, -1, 0)
        edgeResize(binding.ibRightResize, 1, 0)
        edgeResize(binding.ibResizeBottom, 0, 1)
        edgeResize(binding.ibResizeBottomLeft, -1, 1)
        edgeResize(binding.ibResizeBottomRight, 1, 1)

        // The window is laid out in pixels, so the virtual display has to be created in pixels
        // too: creating it from the raw dp values made the app launch into a much smaller
        // display and relaunch as soon as the surface reported its real size.
        val width = config.defaultWindowWidth.dpToPx().toInt()
        val height = config.defaultWindowHeight.dpToPx().toInt()
        newDpi = densityFor(width, height)
        surfaceView.setOnTouchListener(surfaceOnTouchListener)
        surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)

        bubble.ibClose.setOnClickListener {
            isResize = false
            backGestureJob?.cancel()
            backGestureJob = null

            binding.cvappIcon.visibility = View.INVISIBLE

            CoroutineScope(Dispatchers.IO).launch {
                delay(200)

                withContext(Dispatchers.Main) {
                    animateScaleThenResize(
                        binding.cvParent,
                        1F, 1F,
                        0F, 0F,
                        0.5F, 0.5F,
                        0, 0,
                        context
                    ) {
                        onDestroy()
                    }
                }
            }
        }

        bubble.ibFullscreen.setOnClickListener {
            hideSuperBubble()
            getTopRootTask()?.runCatching {
                Instances.activityTaskManager.moveRootTaskToDisplay(taskId, 0)
            }?.onFailure { t ->
                if (t is Error) throw t
                TipUtil.showToast("${t.message}")
            }?.onSuccess {
                bubble.ibClose.callOnClick()
            }
        }

        bubble.ibMinimize.setOnClickListener {
            hideSuperBubble()
            changeMini()
        }

        bubble.ibCollapse.setOnClickListener {
            hideSuperBubble()
            changeCollapsed()
        }

        virtualDisplay = Instances.displayManager.createVirtualDisplay(
            "yamf${System.currentTimeMillis()}", width, height, newDpi, null, flags
        )
        displayId = virtualDisplay.display.displayId
        runCatching {
            (Instances.windowManager as WindowManagerHidden).setDisplayImePolicy(displayId, if (config.showImeInWindow) WindowManagerHidden.DISPLAY_IME_POLICY_LOCAL else WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY)
        }.onFailure { Log.d(TAG, "cannot set the IME policy for display $displayId", it) }
        Instances.activityTaskManager.registerTaskStackListener(taskStackListener)
        (surfaceView as? TextureView)?.surfaceTextureListener = this
        (surfaceView as? SurfaceView)?.holder?.addCallback(this)
        fun watchRotation(attempt: Int) {
            runCatching {
                Instances.iWindowManager.watchRotation(rotationWatcher, displayId)
            }.onFailure {
                // A retry loop keeps a transient failure from overflowing the stack.
                Log.d(TAG, "watchRotation failed (attempt ${attempt + 1})", it)
                if (attempt < 9) {
                    binding.root.postDelayed({ watchRotation(attempt + 1) }, 500)
                } else {
                    Log.e(TAG, "watchRotation gave up after 10 attempts", it)
                }
            }
        }
        watchRotation(0)
        runCatching {
            context.registerReceiver(broadcastReceiver, IntentFilter(ACTION_RESET_ALL_WINDOW), Context.RECEIVER_EXPORTED)
        }.onFailure { Log.d(TAG, "cannot listen for window resets", it) }
    @Volatile
    private var isDestroyed = false

        surfaceView.updateLayoutParams {
            this.width = width
            this.height = height
        }
        binding.vSizePreviewer.updateLayoutParams {
            this.width = width
            this.height = height
        }
        onVirtualDisplayCreated(displayId)

        isResize = false
        binding.cvBackground.post {
            originalWidth = binding.cvBackground.width
            originalHeight = binding.cvBackground.height
            binding.cvBackground.visibility = View.VISIBLE

            binding.cvBackground.radius = config.windowRoundedCorner.dpToPx()
            binding.cvappIcon.radius = config.windowRoundedCorner.dpToPx()


            binding.cvParent.radius = (config.windowRoundedCorner+2).dpToPx()
            originalWidth = binding.cvParent.width
            originalHeight = binding.cvParent.height
            binding.cvParent.visibility = View.VISIBLE

            animateScaleThenResize(
                binding.cvBackground,
                0F, 0F,
                1F, 1F,
                0.5F, 0.5F,
                originalWidth, originalHeight,
                context
            ) {
                setBackgroundWrapContent()

                CoroutineScope(Dispatchers.Main).launch {
                    delay(200)

                    binding.cvParent.strokeWidth = 2.dpToPx().toInt()
                }

                isResize = true
            }
        }

        //TODO: Find me a better alternative for less resource usage instead of polling
        backGestureJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
    /** Opens or closes the action bubble, which floats clear of the window's top edge. */
    private fun toggleSuperBubble() {
        if (isMini || isCollapsed) return
        isSuperShown = !isSuperShown
        if (isSuperShown) {
            positionBubble()
            animateAlpha(bubble.root, 0f, 1f)
        } else {
            hideSuperBubble()
        }
    }

    private fun hideSuperBubble() {
        isSuperShown = false
        if (::bubble.isInitialized && bubble.root.visibility == View.VISIBLE) {
            animateAlpha(bubble.root, 1f, 0f)
        }
    }

    /**
     * Centres the bubble on the window and puts it just outside the window's top edge.
     *
     * The offsets are expressed in the same gravity space the chrome window uses instead of
     * screen coordinates: a view's location does not match the position input events resolve to,
     * which put the bubble a status bar too low. When the window is already flush with the top of
     * the display there is no room above it, so the bubble drops just below the window instead.
     */
    private fun positionBubble() {
        if (isDestroyed || !::bubble.isInitialized) return
        val chromeWidth = binding.root.width
        val chromeHeight = binding.root.height
        if (bubbleWidth == 0 || bubbleHeight == 0 || chromeWidth == 0 || chromeHeight == 0) return
        val gap = 8.dpToPx().toInt()
        val chromeParams = binding.root.layoutParams as WindowManager.LayoutParams

        if (orientation == 0) {
            // Both windows are centred, so equal params.x keeps their centres aligned.
            val chromeTop = (context.display.height - chromeHeight) / 2 + chromeParams.y
            val hasRoomAbove = chromeTop - bubbleHeight - gap >= 0
            bubbleParams.gravity = Gravity.CENTER
            bubbleParams.x = chromeParams.x
            bubbleParams.y = if (hasRoomAbove) {
                chromeParams.y - chromeHeight / 2 - bubbleHeight / 2 - gap
            } else {
                chromeParams.y + chromeHeight / 2 + bubbleHeight / 2 + gap
            }
        } else {
            bubbleParams.gravity = Gravity.TOP or Gravity.START
            bubbleParams.x = chromeParams.x + (chromeWidth - bubbleWidth) / 2
            bubbleParams.y = chromeParams.y - bubbleHeight - gap
            if (bubbleParams.y < 0) {
                bubbleParams.y = chromeParams.y + chromeHeight + gap
            }
        }

        runCatching { Instances.windowManager.updateViewLayout(bubble.root, bubbleParams) }
            .onFailure { Log.d(TAG, "cannot place the action bubble", it) }
    }

                if (isMini || isCollapsed) {
                    withContext(Dispatchers.Main) {
                        bindingLeftBackGesture.root.visibility = View.GONE
                        bindingRightBackGesture.root.visibility = View.GONE
                    }
                } else if (displayId == YAMFManager.currentDisplayId) {
                    withContext(Dispatchers.Main) {
                        bindingLeftBackGesture.root.visibility = View.VISIBLE
                        bindingRightBackGesture.root.visibility = View.VISIBLE
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        bindingLeftBackGesture.root.visibility = View.GONE
                        bindingRightBackGesture.root.visibility = View.GONE
                    }
                }

                if (!isMini && !isCollapsed) {
                    withContext(Dispatchers.Main) {
                        if (orientation == 0) {
                            binding.rlBarControllerBottom.visibility = View.VISIBLE
                        } else {
                            binding.rlBarControllerSide.visibility = View.VISIBLE
                        }
                    }
                }
                delay(500)
            }
        }
    }

    private fun onDestroy() {
        context.unregisterReceiver(broadcastReceiver)
        Instances.iWindowManager.removeRotationWatcher(rotationWatcher)
        Instances.activityTaskManager.unregisterTaskStackListener(taskStackListener)
        YAMFManager.removeWindow(displayId)
        if (::virtualDisplay.isInitialized) {
            runCatching { virtualDisplay.release() }
                .onFailure { Log.d(TAG, "virtual display already released", it) }
        }
        releaseResources()
    }

    private fun getTopRootTask(): ActivityTaskManager.RootTaskInfo? = runCatching {
        Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            .firstOrNull { it.visible }
    }.onFailure { Log.d(TAG, "cannot list root tasks", it) }.getOrNull()

    private fun moveToTop() {
        if (isDestroyed) return
        runCatching {
            Instances.windowManager.removeView(bindingLeftBackGesture.root)
            Instances.windowManager.removeView(bindingRightBackGesture.root)
            Instances.windowManager.addView(bindingLeftBackGesture.root, bindingLeftBackGesture.root.layoutParams)
            Instances.windowManager.addView(bindingRightBackGesture.root, bindingRightBackGesture.root.layoutParams)

            Instances.windowManager.removeView(binding.root)
            Instances.windowManager.addView(binding.root, binding.root.layoutParams)
        }.onFailure { Log.d(TAG, "cannot raise the window", it) }
        YAMFManager.moveToTop(displayId)
    }

    private fun moveToTopIfNeed(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP && YAMFManager.isTop(displayId).not()) {
            moveToTop()
        }
    }

    private fun updateTask(taskInfo: ActivityManager.RunningTaskInfo) {
        RunMainThreadQueue.add {
            if (taskInfo.isVisible.not()) {
                delay(500) // fixme: use a method that directly determines visibility
            }

            var backgroundColor = 0
            var statusBarColor = 0
            var navigationBarColor = 0
            var taskDescription: ActivityManager.TaskDescription?

            if (Build.VERSION.SDK_INT < 35) {
                val topActivity = taskInfo.topActivity ?: return@add
                taskDescription = Instances.activityTaskManager.getTaskDescription(taskInfo.taskId) ?: return@add
                val activityInfo = (Instances.iPackageManager as IPackageManagerHidden).getActivityInfoCompat(topActivity, 0, taskInfo.getObjectAs("userId"))

                backgroundColor = taskDescription.backgroundColor
                statusBarColor = taskDescription.backgroundColor
                navigationBarColor = taskDescription.backgroundColor
                binding.appIcon.setImageDrawable(RoundedDrawable().apply {
                    drawable = runCatching { taskDescription.icon }.getOrNull()?.let { BitmapDrawable(it) } ?: activityInfo.loadIcon(Instances.packageManager)
                    isClipEnabled = true
                    radius = 100
                })
            } else {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningTasks = activityManager.getRunningTasks(5)

                for (task in runningTasks) {
                    if (task.taskId != taskInfo.taskId) continue
                    val packageName = task.baseActivity?.packageName ?: continue
                    runCatching {
                        val description = task.taskDescription
                        backgroundColor = description?.backgroundColor ?: 0
                        statusBarColor = backgroundColor
                        navigationBarColor = backgroundColor
                        binding.appIcon.setImageDrawable(
                            context.packageManager.getApplicationIcon(packageName)
                        )
                    }.onFailure { Log.d(TAG, "cannot resolve the task description", it) }
                }
            }

            if (config.coloredController) {
                val onStateBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(statusBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
                } else {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
                }

                bubble.ibClose.imageTintList = ColorStateList.valueOf(onStateBar)
                binding.background.setBackgroundColor(navigationBarColor)

                val onNavigationBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(navigationBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
                } else {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
                }

                bubble.ibMinimize.imageTintList = ColorStateList.valueOf(onNavigationBar)
                bubble.ibFullscreen.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibRightResize.imageTintList = ColorStateList.valueOf(onNavigationBar)
            }
        }
    }

    fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
        if (isDestroyed) return
        runCatching {
            if (taskInfo.getObject("displayId") == displayId) updateTask(taskInfo)
        }.onFailure { Log.d(TAG, "onTaskMovedToFront failed", it) }
    }

    fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        if (isDestroyed) return
        runCatching {
            if (taskInfo.getObject("displayId") == displayId && taskInfo.isVisible) {
                updateTask(taskInfo)
            }
        }.onFailure { Log.d(TAG, "onTaskDescriptionChanged failed", it) }
    }

    inner class RotationWatcher : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            runMain {
                if (rotateLock.not())
                    rotate(rotation)
            }
        }
    }

    fun rotate(rotation: Int) {
        if (rotation == 1 || rotation == 3) {
            val t = halfHeight
            halfHeight = halfWidth
            halfWidth = t
            val surfaceWidth = surfaceView.width
            val surfaceHeight = surfaceView.height
            binding.vSizePreviewer.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }
            surfaceView.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (isDestroyed) return
        runCatching {
            resizeVirtualDisplay(surface, width, height)
            virtualDisplay.surface = Surface(surface)
        }.onFailure { Log.e(TAG, "cannot attach the window surface", it) }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (isDestroyed || !isResize) return
        runCatching { resizeVirtualDisplay(surface, width, height) }
            .onFailure { Log.e(TAG, "cannot resize the window surface", it) }
    }

    /**
     * Applies the reported surface size to the virtual display. The geometry always goes through
     * [densityFor], so creation and resize cannot apply `reduceDPI` differently.
     */
    private fun resizeVirtualDisplay(surface: SurfaceTexture, width: Int, height: Int) {
        if (isMini || isCollapsed) {
            val targetWidth = width * 2 + halfWidth
            val targetHeight = height * 2 + halfHeight
            newDpi = densityFor(targetWidth, targetHeight)
            virtualDisplay.resize(targetWidth, targetHeight, newDpi)
            surface.setDefaultBufferSize(targetWidth, targetHeight)
        } else {
            newDpi = densityFor(width, height)
            virtualDisplay.resize(width, height, newDpi)
            surface.setDefaultBufferSize(width, height)
            halfWidth = width % 2
            halfHeight = height % 2
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {

    }

    // minimizes the floating window a bar-less only-content floating window
    private fun changeMini() {
        isCollapsed = false
        isResize = false

        if (isMini) {
            isMini = false
            isResize = true
            binding.rootClickMask.visibility = View.GONE

            if (surfaceView is SurfaceView) {
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth
                    height = originalHeight
                }
                setBackgroundWrapContent()
                setParrentWrapContent()
            } else {
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth
                    height = originalHeight
                }
                animateScaleThenResize(
                    binding.cvBackground,
                    0.5F, 0.5F,
                    1F, 1F,
                    0F, 0F,
                    originalWidth, originalHeight,
                    context
                ){
                    setBackgroundWrapContent()
                    setParrentWrapContent()
                    bindingLeftBackGesture.root.visibility = View.VISIBLE
                    bindingRightBackGesture.root.visibility = View.VISIBLE
                }
            }

            binding.ibRightResize.visibility = View.VISIBLE
            binding.ibResizeLeft.visibility = View.VISIBLE
            binding.ibResizeBottom.visibility = View.VISIBLE
            surfaceView.visibility = View.VISIBLE
            surfaceView.setOnTouchListener(surfaceOnTouchListener)
            surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)

            return
        }
        else if (!isMini) {
            binding.rootClickMask.visibility = View.VISIBLE
            isMini = true

            if (config.surfaceView == 1) {
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth/2
                    height = originalHeight/2
                }
            } else {
                animateResize(
                    binding.cvBackground,
                    originalWidth, originalWidth/2,
                    originalHeight, originalHeight/2,
                    context
                ){
                    isResize = true
                    bindingLeftBackGesture.root.visibility = View.GONE
                    bindingRightBackGesture.root.visibility = View.GONE
                }
            }

            binding.ibRightResize.visibility = View.GONE
            surfaceView.setOnTouchListener(null)
            surfaceView.setOnGenericMotionListener(null)

            return
        }
    }

    private fun setBackgroundWrapContent() {
        val layoutParams = binding.cvBackground.layoutParams
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.cvBackground.layoutParams = layoutParams
    }

    private fun setParrentWrapContent() {
        val layoutParams = binding.cvParent.layoutParams
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.cvParent.layoutParams = layoutParams
    }

    private fun changeCollapsed() {
        isResize = false
        if (isCollapsed) {
            binding.rootClickMask.visibility = View.GONE
            expandWindow()
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE
        } else {
            binding.rootClickMask.visibility = View.VISIBLE
            collapseWindow()
            bindingLeftBackGesture.root.visibility = View.GONE
            bindingRightBackGesture.root.visibility = View.GONE
        }
    }

    private fun expandWindow() {
        isCollapsed = false
        binding.background.visibility = View.VISIBLE

        animateResize(
            binding.appIcon, 40.dpToPx().toInt(), 0, 40.dpToPx().toInt(), 0, context) {
            binding.cvappIcon.visibility = View.GONE
            animateResize(binding.cvBackground, 0, originalWidth, 0, originalHeight, context) {
                setBackgroundWrapContent()
                setParrentWrapContent()
                binding.cvappIcon.visibility = View.VISIBLE

                binding.cvappIcon.visibility = View.GONE
                isResize = true
            }
        }
    }

    private fun collapseWindow() {
        isCollapsed = true

        CoroutineScope(Dispatchers.Main).launch {
            delay(200)

            animateResize(binding.cvBackground, binding.cvBackground.width, 0, binding.cvBackground.height, 0, context) {
                binding.cvappIcon.visibility = View.VISIBLE
                binding.background.visibility = View.GONE
                        val windowParams = binding.root.layoutParams as WindowManager.LayoutParams
                        beginWindowX = windowParams.x
                        beginWindowY = windowParams.y
                animateResize(binding.appIcon, 0, 40.dpToPx().toInt(), 0, 40.dpToPx().toInt(), context)

                isResize = true

            }
        }
    }

    private fun calculateScreenInches(width: Int, height: Int): Float {
        val x = (width / context.resources.displayMetrics.xdpi).pow(2)
        val y = (height / context.resources.displayMetrics.ydpi).pow(2)

        return sqrt(x + y)

    }

                            val width = binding.vSizePreviewer.width
                            val height = binding.vSizePreviewer.height
    private fun calculateDpi(width: Int, height: Int, screenSizeInInches: Float): Int {
        val widthSqr = width.toFloat().pow(2)
        val heightSqr = height.toFloat().pow(2)
        val diagonalPixels = sqrt(widthSqr + heightSqr)
                            val windowParams = binding.root.layoutParams as WindowManager.LayoutParams
                            windowParams.x = beginWindowX + horizontal * (width - beginWidth) / 2
                            windowParams.y = beginWindowY + vertical * (height - beginHeight) / 2
                            runCatching { Instances.windowManager.updateViewLayout(binding.root, windowParams) }
                                .onFailure { Log.d(TAG, "cannot move the window after a resize", it) }
                            positionBubble()

        return floor(diagonalPixels / screenSizeInInches).toInt()
    }

    /**
     * Resizes the window from one edge: [horizontal] is -1 for the left edge and +1 for the right,
     * [vertical] is +1 for the bottom edge, and 0 means that axis is not draggable. The window is
     * centred on its own size, so it slides by half the change to keep the opposite edge in place.
     */
    private fun edgeResize(handle: View, horizontal: Int, vertical: Int) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            var beginX = 0F
            var beginY = 0F
            var beginWidth = 0
            var beginHeight = 0
            var beginWindowX = 0
            var beginWindowY = 0

        } catch (t: Throwable) {
            Log.e(TAG, "cannot forward the touch to the virtual display", t)
        } finally {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        beginX = event.rawX
                        beginY = event.rawY
                        binding.vSizePreviewer.layoutParams.let {
                            beginWidth = it.width
                            beginHeight = it.height
                        }
                        binding.vSizePreviewer.visibility = View.VISIBLE
                        binding.cvParent.strokeWidth = 0
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val minimum = 150.dpToPx().toInt()
                        val targetWidth = beginWidth + (event.rawX - beginX).toInt() * horizontal
                        val targetHeight = beginHeight + (event.rawY - beginY).toInt() * vertical
                        binding.vSizePreviewer.updateLayoutParams {
                            width = targetWidth.coerceAtLeast(minimum)
                            height = targetHeight.coerceAtLeast(minimum)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        binding.vSizePreviewer.post {
                            surfaceView.updateLayoutParams {
                                this.width = width
                                this.height = height
                            }
                        }

                        binding.vSizePreviewer.visibility = View.GONE
                        moveToTopIfNeed(event)
                        binding.cvParent.strokeWidth = 2.dpToPx().toInt()
                    }
                }
                return true
            }
        })
    }

    fun forwardMotionEvent(event: MotionEvent) {
        if (isSuperShown) return
        val newEvent = MotionEvent.obtain(event)
        try {
            newEvent.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            Instances.inputManager.injectInputEvent(newEvent, 0)
            newEvent.recycle()
        }
    }

    inner class SurfaceOnTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE
            forwardMotionEvent(event)
            moveToTopIfNeed(event)
            return true
        }
    }

    inner class SurfaceOnGenericMotionListener : View.OnGenericMotionListener {
        override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE
            forwardMotionEvent(event)
            return true
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (isDestroyed) return
        runCatching { virtualDisplay.surface = holder.surface }
            .onFailure { Log.e(TAG, "cannot attach the window surface", it) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (isDestroyed) return
        runCatching {
            newDpi = densityFor(width, height)
            virtualDisplay.resize(width, height, newDpi)
        }.onFailure { Log.e(TAG, "cannot resize the window surface", it) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        runCatching { virtualDisplay.surface = null }
            .onFailure { Log.d(TAG, "the virtual display is already released", it) }
    }

    private val moveGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        var startX = 0
        var startY = 0
        var xAnimation: FlingAnimation? = null
        var yAnimation: FlingAnimation? = null
        var lastX = 0F
        var lastY = 0F
        var last2X = 0F
        var last2Y = 0F
                    positionBubble()

        override fun onDown(e: MotionEvent): Boolean {
            xAnimation?.cancel()
            yAnimation?.cancel()
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            startX = params.x
            startY = params.y
            return true
        }


        override fun onScroll(
            e1: MotionEvent?,
                    positionBubble()
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            e1 ?: return false
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            params.x = (startX + (e2.rawX - e1.rawX)).toInt()
            params.y = (startY + (e2.rawY - e1.rawY)).toInt()
            runCatching { Instances.windowManager.updateViewLayout(binding.root, params) }
                .onFailure { Log.d(TAG, "cannot move the window", it) }
            positionBubble()
            last2X = lastX
            last2Y = lastY
            lastX = e2.rawX
            lastY = e2.rawY
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            e1 ?: return false
            if (e1.source == InputDevice.SOURCE_MOUSE) return false
            val params = binding.root.layoutParams as WindowManager.LayoutParams

            runCatching {
                if (sign(velocityX) != sign(e2.rawX - last2X)) return@runCatching
                xAnimation = flingAnimationOf({
                    params.x = it.toInt()
                    Instances.windowManager.updateViewLayout(binding.root, params)
                }, {
                    params.x.toFloat()
                })
                    .setStartVelocity(velocityX)
                    .setMinValue(0F)
                    .setMaxValue(context.display.width.toFloat() - binding.root.width)
                xAnimation?.start()
            }
            runCatching {
                if (sign(velocityY) != sign(e2.rawY - last2Y)) return@runCatching
                yAnimation = flingAnimationOf({
                    params.y = it.toInt()
                    Instances.windowManager.updateViewLayout(binding.root, params)
                }, {
                    params.y.toFloat()
                })
                    .setStartVelocity(velocityY)
                    .setMinValue(0F)
                    .setMaxValue(context.display.height.toFloat() - binding.root.height)
                yAnimation?.start()
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            when (tapSource) {
                TapSource.NOTCH -> toggleSuperBubble()
                TapSource.ROOT -> if (isMini && !isCollapsed) changeMini()
                else if (!isMini && isCollapsed) changeCollapsed()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // The notch keeps the close shortcut; taps on the window content belong to the app.
            if (tapSource != TapSource.NOTCH) return false
            hideSuperBubble()
            bubble.ibClose.performClick()
            return true
        }
    })
}
