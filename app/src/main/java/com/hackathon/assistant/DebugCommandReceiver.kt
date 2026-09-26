package com.hackathon.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hackathon.assistant.actions.DefaultSkillRegistry
import com.hackathon.assistant.llm.LlmBenchmark
import kotlinx.coroutines.launch

/**
 * Dev/test hook over adb:
 *   --es text "open youtube"                      run a command as if spoken
 *   --es bench gemma-4-E4B-it-gpu.litertlm        benchmark a model (logcat -s LlmBenchmark)
 *   --ez talk true                                same as pressing the assistant button (listen)
 *   --ez dump true                                log the translated current screen (logcat -s ScreenDump)
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as AssistantApp
        // Not goAsync(): benchmarks run for minutes, far past the broadcast deadline.
        app.scope.launch {
            try {
                intent.getStringExtra("bench")?.let { model ->
                    app.llm.close()
                    app.llm.modelName = model
                    LlmBenchmark.run(app.llm, DefaultSkillRegistry().all() + LlmBenchmark.plannedSkills)
                }
                if (intent.getBooleanExtra("dump", false)) {
                    val screen = app.assistant.skillContext.screen
                    val state = screen.capture()
                    Log.i("ScreenDump", state?.let(screen::toPrompt) ?: "accessibility service not connected")
                }
                intent.getStringExtra("text")?.let { app.converse(heard = it) }
                if (intent.getBooleanExtra("talk", false)) app.onTrigger()
            } catch (t: Throwable) {
                Log.e("Assistant", "debug command failed", t)
            }
        }
    }
}
