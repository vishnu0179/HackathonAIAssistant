package com.hackathon.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/** Lets devs and test scripts inject a spoken command as text over adb. */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        val app = context.applicationContext as AssistantApp
        app.scope.launch { app.assistant.handle(text) }
    }
}
