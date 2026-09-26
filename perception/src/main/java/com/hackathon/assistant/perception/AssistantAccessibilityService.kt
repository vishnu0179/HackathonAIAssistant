package com.hackathon.assistant.perception

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/** Entry point for screen reading and control. */
class AssistantAccessibilityService : AccessibilityService() {

    /** Uptime of the last event that changed the UI; drives [ScreenReader.awaitIdle]. */
    @Volatile
    var lastChangeAt = 0L
        private set

    override fun onServiceConnected() {
        instance = this
        // The system accessibility button (nav bar / floating) starts the assistant from any app.
        accessibilityButtonController.registerAccessibilityButtonCallback(
            object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    onTrigger?.invoke()
                }
            },
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastChangeAt = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: AssistantAccessibilityService? = null
            private set

        /** Set by the app: what to do when the user presses the accessibility button. */
        @Volatile
        var onTrigger: (() -> Unit)? = null
    }
}
