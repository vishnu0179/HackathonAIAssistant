package com.hackathon.assistant.actions

import android.content.Intent
import com.hackathon.assistant.core.ActionResult

/** App discovery and deep linking: the agent decides WHICH app reaches the goal and HOW to enter it. */
internal object AppSkills {

    val listApps = SimpleSkill(
        "list_apps", "List installed apps with their deep links; use it to decide which app can do the goal",
        emptyList(), listOf("which app can I use to pay", "what apps do I have"),
    ) {
        val apps = AppCatalog.apps(android)
        ActionResult.Success(
            message = "",
            observation = "Installed apps (name (package): deep links):\n" + apps.joinToString("\n") { AppCatalog.line(it) },
        )
    }

    val openLink = SimpleSkill(
        "open_link", "Open a deep link or web link in the right app, e.g. https://www.youtube.com/results?search_query=lofi or market://details?id=com.whatsapp",
        listOf(
            slot("url", "the full deep link / URL", "Which link should I open?"),
            slot("package", "package name of the app that should open it", "", required = false),
        ),
        listOf("open the whatsapp page on play store"),
    ) { args ->
        val intent = Intent(Intent.ACTION_VIEW, uri(args.getValue("url").trim()))
        args["package"]?.takeIf { it.contains('.') }?.let { intent.setPackage(it.trim()) }
        launch(intent, "")
    }
}
