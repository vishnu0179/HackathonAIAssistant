package com.hackathon.assistant.actions

import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillRegistry

/** All registered skills. Owner: actions. Add new skills to the list below. */
class DefaultSkillRegistry : SkillRegistry {
    private val skills: List<Skill> = listOf<Skill>(
        OpenAppSkill(),
        SmsReaderSkill(),
        WhatsAppSkill(),
    ) + Skills.all
    private val byId = skills.associateBy { it.id }

    override fun all() = skills
    override fun get(id: String) = byId[id]
}
