package com.hackathon.assistant.actions

import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillRegistry

/** All registered skills. Owner: actions. Add new skills to the list below. */
class DefaultSkillRegistry : SkillRegistry {
    private val skills: List<Skill> = listOf<Skill>(
        OpenAppSkill(),
        AppSkills.listApps,
        AppSkills.openLink,
        SmsReaderSkill(),
        WhatsAppSkill(),
        // Wave 1 Android-API skills
        CalendarSkill(),
        ContactLookupSkill(),
        ClipboardSkill(),
        CallLogSkill(),
        LocationSkill(),
        DeviceStatusSkill(),
        MediaControlSkill(),
        // (list_apps is provided by AppSkills.listApps above)
    ) + Skills.all
    private val byId = skills.associateBy { it.id }

    override fun all() = skills
    override fun get(id: String) = byId[id]
}
