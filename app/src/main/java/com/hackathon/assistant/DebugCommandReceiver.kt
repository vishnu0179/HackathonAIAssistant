package com.hackathon.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hackathon.assistant.actions.DefaultSkillRegistry
import com.hackathon.assistant.core.ToolSpec
import com.hackathon.assistant.perception.AccessibilityScreenReader
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
                    LlmBenchmark.run(
                        app.llm,
                        (DefaultSkillRegistry().all() + LlmBenchmark.plannedSkills).map { ToolSpec(it.id, it.description, it.slots) } +
                            Assistant.UI_TOOLS + Assistant.TALK_TOOLS,
                    )
                }
                if (intent.getBooleanExtra("dump", false)) {
                    val screen = app.assistant.skillContext.screen
                    val state = screen.capture()
                    Log.i("ScreenDump", state?.let(screen::toPrompt) ?: "accessibility service not connected")
                }
                if (intent.getBooleanExtra("raw", false)) {
                    val reader = app.assistant.skillContext.screen as AccessibilityScreenReader
                    reader.rawDump().lines().forEach { Log.i("RawDump", it) }
                }
                intent.getStringExtra("text")?.let { app.converse(heard = it) }
                if (intent.getBooleanExtra("talk", false)) app.onTrigger()
            } catch (t: Throwable) {
                Log.e("Assistant", "debug command failed", t)
            }
        }
    }
}
