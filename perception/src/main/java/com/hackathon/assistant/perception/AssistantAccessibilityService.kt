package com.hackathon.assistant.perception

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Entry point for screen reading and control. Owner: perception. */
class AssistantAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TODO(perception): track last UI change time for ScreenReader.awaitIdle().
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
    }
}
