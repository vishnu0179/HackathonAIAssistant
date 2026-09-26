package com.hackathon.assistant.ui

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hackathon.assistant.core.VoiceState
import com.hackathon.assistant.perception.AssistantAccessibilityService
import com.hackathon.assistant.voice.AssistantVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The bottom card over any app. Drawn as an accessibility overlay (the service is already on, so
 * no "draw over apps" prompt), else as an app overlay if that permission was granted.
 * Shows while the assistant is active and hides [HIDE_AFTER_MS] after it goes idle.
 */
class AssistantOverlay(
    private val app: Context,
    private val voice: AssistantVoice,
    private val onMic: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val visible = MutableStateFlow(false)
    private var view: ComposeView? = null
    private var host: Context? = null
    private var owner: OverlayOwner? = null

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.Main) {
            voice.state.collectLatest { state ->
                if (state != VoiceState.IDLE) {
                    attachIfNeeded()
                    visible.value = true
                } else {
                    // Let the last words or the notice be read, then close. A new turn cancels this.
                    delay(if (voice.captions.value.notice.isNotEmpty()) NOTICE_HIDE_MS else HIDE_AFTER_MS)
                    visible.value = false
                }
                updateTouchable(state)
            }
        }
    }

    private fun dismiss() {
        visible.value = false
        updateTouchable(voice.state.value)
        onClose()
    }

    private fun attachIfNeeded() {
        val service = AssistantAccessibilityService.instance
        val target: Context = service ?: app.takeIf { Settings.canDrawOverlays(it) } ?: run {
            Log.w(TAG, "no accessibility service and no overlay permission; card not shown")
            return
        }
        if (view != null && host === target) return
        detach()
        val type = if (service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            BASE_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }

        val lifecycle = OverlayOwner().also { it.start() }
        val compose = ComposeView(target).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setContent {
                val shown by visible.collectAsState()
                val state by voice.state.collectAsState()
                val captions by voice.captions.collectAsState()
                val steps by voice.steps.collectAsState()
                AssistantCardHost(
                    visible = shown,
                    state = state,
                    captions = captions,
                    level = voice.level,
                    onMic = onMic,
                    onClose = ::dismiss,
                    steps = steps,
                )
            }
        }
        runCatching { target.getSystemService(WindowManager::class.java).addView(compose, params) }
            .onFailure { Log.e(TAG, "addView failed", it); lifecycle.destroy(); return }
        view = compose
        host = target
        owner = lifecycle
    }

    /**
     * Touches pass through while hidden, and while THINKING: that's when the agent taps the app
     * underneath, and a fallback gesture must not land on the card.
     */
    private fun updateTouchable(state: VoiceState) {
        val v = view ?: return
        val params = v.layoutParams as? WindowManager.LayoutParams ?: return
        val passThrough = !visible.value || state == VoiceState.THINKING
        val flags = if (passThrough) BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else BASE_FLAGS
        if (params.flags == flags) return
        params.flags = flags
        runCatching { host?.getSystemService(WindowManager::class.java)?.updateViewLayout(v, params) }
    }

    private fun detach() {
        val v = view ?: return
        runCatching { host?.getSystemService(WindowManager::class.java)?.removeView(v) }
        owner?.destroy()
        view = null
        host = null
        owner = null
    }

    /** Compose needs a lifecycle and saved-state owner; an overlay window has neither. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun start() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private companion object {
        const val TAG = "AssistantOverlay"
        const val HIDE_AFTER_MS = 1_200L
        const val NOTICE_HIDE_MS = 1_800L
        const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }
}
