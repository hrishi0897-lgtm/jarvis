package com.jarvis.app

import org.json.JSONArray
import org.json.JSONObject

class Agent(
    private val client: GeminiClient,
    private val tools: Tools
) {

    companion object {
        private const val MAX_TOOL_TURNS = 5
        private const val SYSTEM_PROMPT = """You are Jarvis, an autonomous Android assistant.
EXECUTION RULES:
1. OBSERVE FIRST: If the user asks to tap, click, or open an element on the screen (e.g. an album, a button, a video, a search bar in an app):
   - Call 'read_screen' first to locate exact items and labels.
   - Then call 'click_element' with that exact identifier.
   - NEVER call 'open_app' when the user is referencing a button, tab, or folder inside an app that is already open.
2. APPLICATION LAUNCHING:
   - Call 'open_app' ONLY when explicitly asked to open a completely separate application by name (e.g. "open WhatsApp", "open Gallery").
3. WHATSAPP:
   - "Send [msg] to [contact]" -> 'send_whatsapp_message'.
   - Already in a chat -> 'type_and_send'.
4. NATIVE NAVIGATION:
   - Navigation: 'phone_control' ('back', 'home', 'recents', 'notifications', 'quick_settings').
   - Paging/Scrolling: 'swipe_screen'.
   - Date, time, battery: 'get_device_status'. NEVER use Termux commands for basic status.
5. NO TERMUX UNLESS EXPLICIT:
   - Only call 'run_termux_command' if the user explicitly asks for bash, terminal, shell command, or script execution.
6. CONCISE: Speak only 1 brief spoken sentence.
"""
    }

    suspend fun runTurn(
        conversationHistory: MutableList<JSONObject>,
        userMessage: String,
        onUpdate: (String) -> Unit
    ): String {
        // Enforce strict history size to stay under Gemini 15 RPM token payload limits
        if (conversationHistory.size > 6) {
            val pruned = conversationHistory.takeLast(2).toMutableList()
            conversationHistory.clear()
            conversationHistory.addAll(pruned)
        }

        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", userMessage) })
            })
        })

        var turns = 0
        while (turns < MAX_TOOL_TURNS) {
            turns++

            val contentsArray = JSONArray()
            for (msg in conversationHistory) {
                contentsArray.put(msg)
            }

            val response = client.generateContent(
                contents = contentsArray,
                tools = tools.getToolDeclarations(),
                systemInstruction = SYSTEM_PROMPT
            )

            val candidates = response.optJSONArray("candidates") ?: break
            if (candidates.length() == 0) break

            val firstCandidate = candidates.getJSONObject(0)
            val modelContent = firstCandidate.optJSONObject("content") ?: break
            val parts = modelContent.optJSONArray("parts") ?: break

            var functionCallFound = false

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("functionCall")) {
                    functionCallFound = true
                    val functionCall = part.getJSONObject("functionCall")
                    val name = functionCall.getString("name")
                    val args = functionCall.optJSONObject("args") ?: JSONObject()

                    onUpdate("Processing...")
                    val toolResult = tools.execute(name, args)

                    conversationHistory.add(modelContent)
                    conversationHistory.add(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("functionResponse", JSONObject().apply {
                                    put("name", name)
                                    put("response", toolResult)
                                })
                            })
                        })
                    })

                    // Single-turn early completions to save RPM and round-trip latency
                    if (name == "send_whatsapp_message" && toolResult.optString("status") == "success") {
                        val reply = "Sent."
                        appendSyntheticModelReply(conversationHistory, reply)
                        return reply
                    }
                    if (name == "type_and_send" && toolResult.optString("status") == "success") {
                        val reply = "Sent."
                        appendSyntheticModelReply(conversationHistory, reply)
                        return reply
                    }
                    if (name == "phone_control" && toolResult.optString("status") == "success") {
                        val reply = "Done."
                        appendSyntheticModelReply(conversationHistory, reply)
                        return reply
                    }
                    if (name == "swipe_screen" && toolResult.optString("status") == "success") {
                        val reply = "Done."
                        appendSyntheticModelReply(conversationHistory, reply)
                        return reply
                    }
                    break
                }
            }

            if (!functionCallFound) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.has("text")) {
                        val replyText = part.getString("text").trim()
                        conversationHistory.add(modelContent)
                        return replyText
                    }
                }
                break
            }
        }

        val fallback = "Done."
        appendSyntheticModelReply(conversationHistory, fallback)
        return fallback
    }

    private fun appendSyntheticModelReply(history: MutableList<JSONObject>, text: String) {
        history.add(JSONObject().apply {
            put("role", "model")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", text) })
            })
        })
    }
}
