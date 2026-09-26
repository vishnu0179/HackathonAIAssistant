package com.hackathon.assistant.actions

import android.content.Intent
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/** Launches an installed app by its spoken name. Reference pattern for new skills. */
class OpenAppSkill : Skill {
    override val id = "open_app"
    override val description = "Open an installed app by name"
    override val slots = listOf(
        SlotSpec("app", "Name of the app, e.g. YouTube", question = "Which app should I open?"),
    )
    override val examples = listOf("open youtube", "launch camera", "start whatsapp")

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
        val wanted = args["app"]?.trim()?.lowercase()
            ?: return ActionResult.Failure("No app name")
        val pm = ctx.android.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .minByOrNull { (_, label) -> matchScore(label.lowercase(), wanted) }
            ?.takeIf { (_, label) -> matchScore(label.lowercase(), wanted) < NO_MATCH }
            ?: return ActionResult.Failure("I couldn't find an app called ${args["app"]}")
        val intent = pm.getLaunchIntentForPackage(match.first)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return ActionResult.Failure("${match.second} can't be opened")
        ctx.android.startActivity(intent)
        return ActionResult.Success("Opening ${match.second}")
    }

    /** Lower is better: exact, then prefix, then substring. */
    private fun matchScore(label: String, wanted: String) = when {
        label == wanted -> 0
        label.startsWith(wanted) -> 1
        label.contains(wanted) || wanted.contains(label) -> 2
        else -> NO_MATCH
    }

    private companion object { const val NO_MATCH = 99 }
}
