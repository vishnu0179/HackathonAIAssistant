package com.hackathon.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractJsonTest {
    @Test fun strayConstrainedDecoderPrefix() {
        val json = extractJson("""{"{"type":"skill","skill":"open_app","args":{"app":"youtube"}}}""")!!
        assertEquals("skill", json.getString("type"))
        assertEquals("youtube", json.getJSONObject("args").getString("app"))
    }

    @Test fun codeFenceAndChatter() {
        val json = extractJson("Sure!\n```json\n{\"action\": \"back\", \"reason\": \"x\"}\n```")!!
        assertEquals("back", json.getString("action"))
    }

    @Test fun garbage() {
        assertNull(extractJson("no json here {"))
    }
}
