package com.jarvis.app

import org.json.JSONArray
import org.json.JSONObject

class Agent(
    private val client: GeminiClient,
    private val tools: Tools
) {

    companion object {
        private const val MAX_TOOL_TURNS = 12
        private const val SYSTEM_PROMPT = """You are Jarvis, a fast and helpful native Android voice assistant.
Follow these guidelines strictly:
1. Speak concisely in natural conversational sentences suitable for speech.
2. For messaging apps (e.g. WhatsApp), use 'list_recent_messages' or 'read_screen' to inspect chats.
3. To open an app or navigate, always use 'open_app'. DO NOT run Termux commands (like am start) to open normal apps.
4. To interact with elements on screen, use 'read_screen' first to see visible options, then call 'click_element' or 'input_text_element'.
5. If a button, chat item, contact, or field is not visible in 'read_screen', call 'scroll_screen' with direction 'down' or 'up', then call 'read_screen' again to locate it.
6. Only use 'run_termux_command' when the user explicitly requests command line, scripts, or system shell utilities.
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

                    onUpdate("Executing $name...")

                    val toolResult = tools.execute(name, args)

                    // Retain the entire untouched model content to preserve thought_signatures
                    conversationHistory.add(modelContent)

                    // Add function response turn under role "user"
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

        return "I completed the requested action."
    }
}
