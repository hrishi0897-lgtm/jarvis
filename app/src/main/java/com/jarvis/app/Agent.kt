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
CRITICAL SPEED INSTRUCTIONS:
1. Act decisively with minimal tool calls. Execute actions immediately in turn 1 whenever possible.
2. For typing/sending messages in an active app (e.g. WhatsApp): IMMEDIATELY call 'type_and_send'. DO NOT call 'read_screen' first.
3. For phone navigation ('back', 'home', 'recents', 'notifications'): call 'phone_control' directly.
4. For status/date/time: call 'get_device_status' directly.
5. Only call 'read_screen' if you must inspect specific text or choices on screen before tapping.
6. When an action succeeds, return a concise spoken answer in 1 short sentence.
"""
    }

    suspend fun runTurn(
        conversationHistory: MutableList<JSONObject>,
        userMessage: String,
        onUpdate: (String) -> Unit
    ): String {
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
            // Keep conversation concise to reduce token upload latency
            val recentHistory = conversationHistory.takeLast(8)
            for (msg in recentHistory) {
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

                    // If a direct navigation or typing action completed, return immediately without another round-trip
                    if (name == "type_and_send" && toolResult.optString("status") == "success") {
                        return "Sent."
                    }
                    if (name == "phone_control" && toolResult.optString("status") == "success") {
                        return "Done."
                    }
                    if (name == "swipe_screen" && toolResult.optString("status") == "success") {
                        return "Done."
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

        return "Done."
    }
}
