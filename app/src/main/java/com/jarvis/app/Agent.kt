package com.jarvis.app

import org.json.JSONArray
import org.json.JSONObject

class Agent(
    private val client: GeminiClient,
    private val tools: Tools
) {

    companion object {
        private const val MAX_TOOL_TURNS = 6
        private const val SYSTEM_PROMPT = """You are Jarvis, a fast native Android voice assistant.
CRITICAL GUIDELINES:
1. When asked to send a message to someone on WhatsApp (e.g. "send hi to Jerin", "message mom hello", "whatsapp John"), IMMEDIATELY call 'send_whatsapp_message' with recipient and message.
2. If already inside an open chat conversation and the user says "type X" or "send X", call 'type_and_send'.
3. When on screen with navigation tabs or buttons (like "Search", "Home", "Library", "Premium" in Spotify, YouTube, etc.) and the user says "open search" or "click search", call 'click_element' with 'Search' instead of opening an external app.
4. For phone navigation ('back', 'home', 'recents', 'notifications'): call 'phone_control' directly.
5. For date, time, battery: call 'get_device_status' directly. NEVER use Termux commands for basic status.
6. ONLY call 'run_termux_command' if the user explicitly mentions bash, terminal, shell command, or script.
7. Speak concisely in 1 short spoken sentence.
"""
    }

    suspend fun runTurn(
        conversationHistory: MutableList<JSONObject>,
        userMessage: String,
        onUpdate: (String) -> Unit
    ): String {
        if (conversationHistory.size > 20) {
            conversationHistory.clear()
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

                    onUpdate("Working...")
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
