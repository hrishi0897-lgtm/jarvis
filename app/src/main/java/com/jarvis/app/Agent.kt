package com.jarvis.app

import org.json.JSONArray
import org.json.JSONObject

class Agent(
    private val client: GeminiClient,
    private val tools: Tools
) {

    companion object {
        private const val MAX_TOOL_TURNS = 14
        private const val SYSTEM_PROMPT = """You are Jarvis, an autonomous Android assistant capable of controlling the smartphone completely.
RULES:
1. Speak concisely in natural spoken English suitable for speech.
2. For current date, time, or battery percentage, ALWAYS use 'get_device_status'. NEVER run Termux shell commands for date or status.
3. For messaging apps (WhatsApp, Telegram, etc.):
   - When the user asks to type, write, or send a message (e.g. "type hi", "send hello"), ALWAYS call 'type_and_send' with the text.
   - DO NOT click random message bubbles or attempt shell scripts to type.
4. For phone navigation:
   - Go back: call 'phone_control' with action 'back'.
   - Go home: call 'phone_control' with action 'home'.
   - Switch apps/multitask: call 'phone_control' with action 'recents'.
   - Notifications shade: call 'phone_control' with action 'notifications'.
   - Split screen: call 'phone_control' with action 'split_screen'.
5. For scrolling or paging: call 'swipe_screen' with 'down', 'up', 'left', or 'right'.
6. For other UI interactions: call 'read_screen' first, then 'click_element' or 'input_text_element'.
7. CRITICAL RESTRICTION ON TERMUX:
   - ONLY use 'run_termux_command' when the user explicitly mentions "bash", "terminal", "shell command", or "script".
8. CONFIRMATION POLICY:
   - For opening apps, navigating, scrolling, reading, typing, and standard tapping, DO NOT ask for confirmation. Execute immediately.
   - ONLY call 'request_destructive_action' when an operation involves deleting chats, removing messages, clearing data, uninstalling apps, or wiping files.
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

        return "Completed."
    }
}
