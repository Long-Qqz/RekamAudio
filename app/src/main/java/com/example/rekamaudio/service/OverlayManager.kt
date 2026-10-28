package com.example.rekamaudio.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.rekamaudio.R
import com.example.rekamaudio.ui.theme.RekamAudioTheme
import kotlin.math.roundToInt

class OverlayManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isOverlayShown = false

    // State to drive the Compose UI
    private var buttonState by mutableStateOf(OverlayButtonState.IDLE)
    private var encodeProgress by mutableStateOf(0f)
    private var warningMessage by mutableStateOf<String?>(null)
    private var warningExpanded = false
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    fun isShowing(): Boolean = isOverlayShown

    /**
     * Shows a short hint next to the floating button. The overlay window grows to
     * the left, and its x position is shifted by the same amount so the button
     * itself does not jump while the hint appears or disappears.
     */
    fun showWarning(message: String) {
        if (warningMessage == message) return
        val wasExpanded = warningExpanded
        warningMessage = message
        if (!wasExpanded) {
            warningExpanded = true
            shiftAnchor(-warningWidthPx())
        }
    }

    fun hideWarning() {
        if (warningMessage == null) return
        warningMessage = null
        if (warningExpanded) {
            warningExpanded = false
            shiftAnchor(warningWidthPx())
        }
    }

    private fun warningWidthPx(): Int {
        val density = context.resources.displayMetrics.density
        return ((WARNING_WIDTH_DP + WARNING_GAP_DP) * density).toInt()
    }

    private fun shiftAnchor(deltaX: Int) {
        val params = windowParams ?: return
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val bounds = screenBounds()
        val viewWidth = if (view.width > 0) {
            view.width
        } else {
            (OVERLAY_WINDOW_SIZE_DP * context.resources.displayMetrics.density).toInt()
        }
        params.x = (params.x + deltaX).coerceIn(0, (bounds.width() - viewWidth).coerceAtLeast(0))
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun showOverlay(onRecordClick: () -> Unit, onStopClick: () -> Unit, onCloseClick: () -> Unit, isRecording: Boolean) {
        if (isOverlayShown) {
            updateState(isRecording)
            return
        }

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = createOverlayParams()
        windowParams = params

        // Initialize Lifecycle Owner
        lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner?.performRestore(null)
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        overlayView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)

            setContent {
                RekamAudioTheme(
                    dynamicColor = true // Ensure dynamic colors matching the app
                ) {
                    OverlayContent(
                        buttonState = buttonState,
                        encodeProgress = encodeProgress,
                        warningMessage = warningMessage,
                        onRecordClick = onRecordClick,
                        onStopClick = onStopClick,
                        onCloseClick = onCloseClick,
                        onDrag = { dx, dy -> moveBy(dx, dy) }
                    )
                }
            }
        }

        // Start Lifecycle
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        updateState(isRecording)

        try {
            windowManager?.addView(overlayView, params)
            isOverlayShown = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createOverlayParams(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        val density = context.resources.displayMetrics.density
        // Appear near the top-right corner (window size + margin)
        params.x = (screenBounds().width() - (OVERLAY_WINDOW_SIZE_DP + 24) * density).toInt().coerceAtLeast(0)
        params.y = (110 * density).toInt().coerceAtLeast(0)
        return params
    }

    /**
     * Moves the overlay by the given pixel delta, keeping it fully on screen.
     */
    fun moveBy(dx: Float, dy: Float) {
        val params = windowParams ?: return
        val view = overlayView ?: return
        val wm = windowManager ?: return
        if (dx == 0f && dy == 0f) return

        val bounds = screenBounds()
        val density = context.resources.displayMetrics.density
        val viewWidth = if (view.width > 0) view.width else (OVERLAY_WINDOW_SIZE_DP * density).toInt()
        val viewHeight = if (view.height > 0) view.height else (OVERLAY_WINDOW_SIZE_DP * density).toInt()

        params.x = (params.x + dx).roundToInt().coerceIn(0, (bounds.width() - viewWidth).coerceAtLeast(0))
        params.y = (params.y + dy).roundToInt().coerceIn(0, (bounds.height() - viewHeight).coerceAtLeast(0))

        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun screenBounds(): Rect {
        val wm = windowManager ?: return Rect()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.maximumWindowMetrics.bounds
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    fun removeOverlay() {
        if (isOverlayShown && overlayView != null) {
            try {
                // End Lifecycle
                lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

                windowManager?.removeView(overlayView)
                overlayView = null
                windowParams = null
                lifecycleOwner = null
                isOverlayShown = false
                warningMessage = null
                warningExpanded = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateState(isRecording: Boolean) {
        buttonState = when {
            isRecording -> OverlayButtonState.RECORDING
            buttonState == OverlayButtonState.RECORDING -> OverlayButtonState.IDLE
            // A late "stopped" event must not cancel the encoding state that is
            // entered right after the user taps stop.
            else -> buttonState
        }
    }

    fun showEncoding() {
        buttonState = OverlayButtonState.ENCODING
        encodeProgress = 0f
    }

    fun updateEncodingProgress(progress: Float) {
        if (buttonState == OverlayButtonState.ENCODING) {
            encodeProgress = progress.coerceIn(0f, 1f)
        }
    }

    fun hideEncoding() {
        encodeProgress = 0f
        if (buttonState == OverlayButtonState.ENCODING) {
            buttonState = OverlayButtonState.IDLE
        }
    }

    companion object {
        // Window / touch area big enough to also fit the pulse halo and drag shadow
        private const val OVERLAY_WINDOW_SIZE_DP = 80
    }
}

private enum class OverlayButtonState {
    IDLE,
    RECORDING,
    ENCODING
}

private val OVERLAY_WINDOW_SIZE = 80.dp
private val BUTTON_SIZE = 52.dp
private val BADGE_SIZE = 24.dp

// Hint bubble shown to the left of the button. Shared with the window manager
// so the x offset applied when it appears matches the space it takes up.
private const val WARNING_WIDTH_DP = 150
private const val WARNING_GAP_DP = 6

@Composable
private fun OverlayContent(
    buttonState: OverlayButtonState,
    encodeProgress: Float,
    warningMessage: String?,
    onRecordClick: () -> Unit,
    onStopClick: () -> Unit,
    onCloseClick: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "dragScale"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (warningMessage != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .width(WARNING_WIDTH_DP.dp)
                    .padding(end = WARNING_GAP_DP.dp)
            ) {
                Text(
                    text = warningMessage,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 4,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(OVERLAY_WINDOW_SIZE)
        ) {
        // Pulsing halo while recording
        if (buttonState == OverlayButtonState.RECORDING) {
            val infiniteTransition = rememberInfiniteTransition(label = "recordingPulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 0.9f,
                targetValue = 1.4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulseScale"
            )
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulseAlpha"
            )
            Box(
                modifier = Modifier
                    .size(BUTTON_SIZE)
                    .scale(pulseScale)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha), CircleShape)
            )
        }

        val buttonModifier = Modifier
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                )
            }
            .graphicsLayer {
                scaleX = dragScale
                scaleY = dragScale
            }
            .size(BUTTON_SIZE)

        if (buttonState == OverlayButtonState.ENCODING) {
            // Determinate ring: the user must always see how far along the
            // encoding is. A spinner gives no signal at all about whether the
            // work is moving, which is exactly what made a slow flush look
            // like a freeze.
            val realProgress = encodeProgress.coerceIn(0f, 1f)
            // Creep keeps the arc alive when ffmpeg reports nothing yet; it
            // stops at 90% so it never claims completion prematurely.
            val creep = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                creep.animateTo(
                    targetValue = 0.9f,
                    animationSpec = tween(12000, easing = LinearOutSlowInEasing)
                )
            }
            val shownProgress = maxOf(realProgress, creep.value).coerceIn(0f, 1f)
            val sweepAngle by animateFloatAsState(
                targetValue = 360f * shownProgress,
                animationSpec = tween(250),
                label = "encodingSweep"
            )
            val percent = (shownProgress * 100).toInt().coerceIn(0, 100)

            Surface(
                onClick = onStopClick,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shadowElevation = if (isDragging) 12.dp else 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
                modifier = buttonModifier
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val ringColor = MaterialTheme.colorScheme.onSecondaryContainer
                    Canvas(modifier = Modifier.size(44.dp)) {
                        val stroke = 4.dp.toPx()
                        val inset = stroke / 2
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        // Background track
                        drawArc(
                            color = ringColor.copy(alpha = 0.15f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = stroke)
                        )
                        // Completed portion
                        drawArc(
                            color = ringColor,
                            startAngle = -90f,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        } else {
            // Main circular button – tap to toggle recording, drag to move
            Surface(
                onClick = if (buttonState == OverlayButtonState.RECORDING) onStopClick else onRecordClick,
                shape = CircleShape,
                color = if (buttonState == OverlayButtonState.RECORDING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentColor = if (buttonState == OverlayButtonState.RECORDING) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                shadowElevation = if (isDragging) 12.dp else 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
                modifier = buttonModifier
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (buttonState == OverlayButtonState.RECORDING) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = stringResource(
                            if (buttonState == OverlayButtonState.RECORDING) R.string.overlay_stop else R.string.overlay_record
                        ),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        // Small close badge on the top-right corner (only when idle)
        if (buttonState == OverlayButtonState.IDLE) {
            Surface(
                onClick = onCloseClick,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-8).dp, y = 8.dp)
                    .size(BADGE_SIZE)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.overlay_close),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
        }
    }
}

// Custom Lifecycle Owner for WindowManager-attached ComposeViews
private class OverlayLifecycleOwner : LifecycleRegistryOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    fun performRestore(savedState: Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }
}

// Simple Interface helper if LifecycleOwner clashes
private interface LifecycleRegistryOwner : androidx.lifecycle.LifecycleOwner {
    override val lifecycle: Lifecycle
}
