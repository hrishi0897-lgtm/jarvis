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
1. ON-SCREEN NAVIGATION:
   - When inside WhatsApp, Gallery, Spotify, or any active app and told to open or click a chat, album, or button (e.g. "open football chat", "open footballu", "open camera album", "click search"):
     Call 'click_element' directly with that name. DO NOT call 'open_app'.
2. APP LAUNCHING:
   - Call 'open_app' ONLY when launching an application from outside (e.g. "open WhatsApp", "open YouTube").
3. MESSAGING:
   - "Send [msg] to [contact]" -> 'send_whatsapp_message'.
   - In active chat: "type [msg]" or "send [msg]" -> 'type_and_send'.
4. NATIVE NAVIGATION:
   - Back/Home/Recents: 'phone_control'.
   - Scrolling: 'swipe_screen'.
   - Date, time, battery: 'get_device_status'.
5. SPEAKING:
   - Keep answers to 1 concise sentence.
"""
    }

    suspend fun runTurn(
        conversationHistory: MutableList<JSONObject>,
        userMessage: String,
        onUpdate: (String) -> Unit
    ): String {
        // Enforce clean session state to eliminate unlinked function turns & save RPM quota
        conversationHistory.clear()

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

                    // Append model functionCall turn followed immediately by user functionResponse
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

                    // Immediate returns with synthetic model closure
                    if (name == "click_element" && toolResult.optString("status") == "success") {
                        val reply = "Opened."
                        appendSyntheticModelReply(conversationHistory, reply)
                        return reply
                    }
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
