package com.hackathon.assistant.actions

import android.content.Context
import android.provider.Telephony

/**
 * Reads SMS from the device inbox via ContentResolver.
 *
 * Kept as a plain data-access object so any skill (or future code) can call it
 * without going through the Skill interface. The Skill layer decides *what* to
 * fetch; this layer only decides *how* to read it from the OS.
 */
internal object SmsRepository {

    data class Sms(
        val sender: String,   // phone number or contact name if resolved
        val body: String,
        val timestampMs: Long,
        val isRead: Boolean,
        val threadId: Long,
    )

    /** Inbox folders the OS exposes. */
    enum class Folder(val uri: android.net.Uri) {
        INBOX(Telephony.Sms.Inbox.CONTENT_URI),
        SENT(Telephony.Sms.Sent.CONTENT_URI),
        ALL(Telephony.Sms.CONTENT_URI),
    }

    /**
     * Returns messages sorted newest-first.
     *
     * @param folder  which mailbox to read
     * @param limit   max messages to return (keep small — model context is finite)
     * @param onlyUnread  when true, filters to unread messages only
     * @param senderFilter  when non-null, keeps only messages from senders whose
     *                      address contains this substring (case-insensitive)
     */
    fun query(
        context: Context,
        folder: Folder = Folder.INBOX,
        limit: Int = 30,
        onlyUnread: Boolean = false,
        senderFilter: String? = null,
    ): List<Sms> {
        val selection = buildList<String> {
            if (onlyUnread) add("${Telephony.Sms.READ} = 0")
            if (senderFilter != null) add("${Telephony.Sms.ADDRESS} LIKE ?")
        }.joinToString(" AND ").ifEmpty { null }

        val selectionArgs = buildList<String> {
            if (senderFilter != null) add("%$senderFilter%")
        }.toTypedArray().ifEmpty { null }

        val out = mutableListOf<Sms>()
        context.contentResolver.query(
            folder.uri,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.THREAD_ID,
            ),
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC LIMIT $limit",
        )?.use { cursor ->
            val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            val threadIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)

            while (cursor.moveToNext()) {
                out += Sms(
                    sender = cursor.getString(addrIdx) ?: "Unknown",
                    body = cursor.getString(bodyIdx) ?: "",
                    timestampMs = cursor.getLong(dateIdx),
                    isRead = cursor.getInt(readIdx) == 1,
                    threadId = cursor.getLong(threadIdx),
                )
            }
        }
        return out
    }

    /**
     * Format a list of SMS messages into a compact prompt-friendly string.
     * Rakshit's speaking tool reads this text aloud; the LLM uses it to answer
     * questions about messages.
     *
     * Example output:
     *   [1] From: +91 98765 43210 | Today 10:42 AM
     *       "Your OTP is 482910. Do not share."
     *   [2] From: Mom | Yesterday 6:15 PM
     *       "Call me when you reach"
     */
    fun format(messages: List<Sms>, resolveNames: Boolean = true, context: Context? = null): String {
        if (messages.isEmpty()) return "No messages found."
        val contacts: Map<String, String> = if (resolveNames && context != null) {
            runCatching { loadContactMap(context) }.getOrDefault(emptyMap())
        } else emptyMap()

        val now = System.currentTimeMillis()
        return messages.mapIndexed { i, sms ->
            val name = contacts[normalizeNumber(sms.sender)] ?: sms.sender
            val time = relativeTime(sms.timestampMs, now)
            val preview = sms.body.take(120).replace('\n', ' ')
            "[${i + 1}] From: $name | $time\n    \"$preview\""
        }.joinToString("\n")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun normalizeNumber(raw: String) = raw.filter { it.isDigit() }.takeLast(10)

    private fun loadContactMap(context: Context): Map<String, String> {
        val map = mutableMapOf<String, String>()
        context.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = normalizeNumber(c.getString(1) ?: continue)
                if (number.isNotEmpty()) map[number] = name
            }
        }
        return map
    }

    private fun relativeTime(ts: Long, now: Long): String {
        val diff = now - ts
        val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        val fullSdf = java.text.SimpleDateFormat("d MMM h:mm a", java.util.Locale.getDefault())
        return when {
            diff < 60_000L -> "Just now"
            diff < 3_600_000L -> "${diff / 60_000}m ago"
            diff < 24 * 3_600_000L -> "Today ${sdf.format(java.util.Date(ts))}"
            diff < 48 * 3_600_000L -> "Yesterday ${sdf.format(java.util.Date(ts))}"
            else -> fullSdf.format(java.util.Date(ts))
        }
    }
}
