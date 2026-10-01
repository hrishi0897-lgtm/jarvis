package com.jarvis.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

enum class VoiceState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

class VoiceActivity : ComponentActivity(), RecognitionListener, TextToSpeech.OnInitListener {

    private lateinit var prefsManager: PreferencesManager
    private lateinit var geminiClient: GeminiClient
    private lateinit var tools: Tools
    private lateinit var agent: Agent

    private val activityScope = MainScope()
    private val conversationHistory = mutableListOf<JSONObject>()

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    private var voiceState by mutableStateOf(VoiceState.IDLE)
    private var userCaption by mutableStateOf("")
    private var jarvisCaption by mutableStateOf("Jarvis is listening...")
    private var micLevel by mutableFloatOf(0f)
    private var isMuted by mutableStateOf(false)

    private var consecutiveSilenceCount = 0
    private var hasFocusGained = false

    private var confirmContinuation: ((Boolean) -> Unit)? = null
    private var showConfirmationState by mutableStateOf<String?>(null)

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startListeningSession()
        } else {
            jarvisCaption = "Microphone permission required."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefsManager = PreferencesManager(applicationContext)
        geminiClient = GeminiClient { prefsManager.getApiKey() }

        val confirmHandler: suspend (String) -> Boolean = { details ->
            suspendCancellableCoroutine<Boolean> { continuation: CancellableContinuation<Boolean> ->
                confirmContinuation = { allowed: Boolean ->
                    if (continuation.isActive) {
                        continuation.resume(allowed)
                    }
                }
                showConfirmationState = details
            }
        }

        val termuxBridge = TermuxBridge(applicationContext, confirmHandler)
        tools = Tools(applicationContext, prefsManager, termuxBridge, confirmHandler)
        agent = Agent(geminiClient, tools)

        tts = TextToSpeech(this, this)

        setContent {
            VoiceScreenContent()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasFocusGained) {
            hasFocusGained = true
            checkPermissionAndListen()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        stopSpeaking()
        checkPermissionAndListen()
    }

    private fun checkPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListeningSession()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun ensureRecognizer() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(this@VoiceActivity)
            }
        }
    }

    private fun startListeningSession() {
        if (isMuted) return
        stopSpeaking()
        ensureRecognizer()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        voiceState = VoiceState.LISTENING
        micLevel = 0f
        userCaption = ""
        speechRecognizer?.startListening(intent)
    }

    private fun stopListeningSession() {
        speechRecognizer?.stopListening()
        voiceState = VoiceState.IDLE
        micLevel = 0f
    }

    private fun stopSpeaking() {
        if (tts?.isSpeaking == true) {
            tts?.stop()
        }
        if (voiceState == VoiceState.SPEAKING) {
            voiceState = VoiceState.IDLE
        }
    }

    private fun speakText(text: String) {
        if (!isTtsInitialized || tts == null) {
            startListeningSession()
            return
        }

        voiceState = VoiceState.SPEAKING
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "JARVIS_RESPONSE")
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "JARVIS_RESPONSE")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isTtsInitialized = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    voiceState = VoiceState.SPEAKING
                }

                override fun onDone(utteranceId: String?) {
                    activityScope.launch(Dispatchers.Main) {
                        consecutiveSilenceCount = 0
                        startListeningSession()
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    activityScope.launch(Dispatchers.Main) {
                        startListeningSession()
                    }
                }
            })
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        voiceState = VoiceState.LISTENING
    }

    override fun onRmsChanged(rmsdB: Float) {
        if (voiceState == VoiceState.LISTENING) {
            micLevel = (rmsdB / 10f).coerceIn(0f, 1.2f)
        }
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            userCaption = matches[0]
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim() ?: ""

        if (text.isNotBlank()) {
            consecutiveSilenceCount = 0
            userCaption = text
            voiceState = VoiceState.THINKING
            jarvisCaption = "Thinking..."

            activityScope.launch {
                try {
                    val reply = agent.runTurn(
                        conversationHistory = conversationHistory,
                        userMessage = text,
                        onUpdate = { status -> jarvisCaption = status }
                    )
                    jarvisCaption = reply
                    speakText(reply)
                } catch (e: Exception) {
                    val error = "Error: ${e.message}"
                    jarvisCaption = error
                    speakText(error)
                }
            }
        } else {
            handleSilence()
        }
    }

    override fun onError(error: Int) {
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            handleSilence()
        } else {
            voiceState = VoiceState.IDLE
            jarvisCaption = "Tap the orb to speak."
        }
    }

    private fun handleSilence() {
        consecutiveSilenceCount++
        if (consecutiveSilenceCount >= 2) {
            finish()
        } else {
            startListeningSession()
        }
    }

    override fun onBeginningOfSpeech() {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    @Composable
    fun VoiceScreenContent() {
        val infiniteTransition = rememberInfiniteTransition(label = "orb_motion")

        val rotationAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (voiceState == VoiceState.THINKING) 1200 else 6000,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )

        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (voiceState == VoiceState.SPEAKING) 500 else 1800,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )

        val backgroundBrush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFF9FBFC),
                Color(0xFFEAF2F8),
                Color(0xFFD8E9F6)
            )
        )

        MaterialTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundBrush)
                    .padding(24.dp)
            ) {
                IconButton(
                    onClick = { finish() },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.08f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("✕", fontSize = 16.sp, color = Color.DarkGray)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(240.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                stopSpeaking()
                                consecutiveSilenceCount = 0
                                startListeningSession()
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val baseRadius: Float = size.minDimension / 2.6f
                            val dynamicRadius: Float = if (voiceState == VoiceState.LISTENING) {
                                baseRadius * (1f + micLevel * 0.4f)
                            } else {
                                baseRadius * pulseScale
                            }

                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color(0x554285F4), Color.Transparent),
                                    center = center,
                                    radius = dynamicRadius * 1.35f
                                ),
                                radius = dynamicRadius * 1.35f,
                                center = center
                            )

                            rotate(rotationAngle, pivot = center) {
                                drawCircle(
                                    brush = Brush.sweepGradient(
                                        colors = listOf(
                                            Color(0xFF4285F4),
                                            Color(0xFF9B51E0),
                                            Color(0xFF00C9FF),
                                            Color(0xFF4285F4)
                                        ),
                                        center = center
                                    ),
                                    radius = dynamicRadius,
                                    center = center
                                )
                            }

                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color.White.copy(alpha = 0.65f), Color.Transparent),
                                    center = Offset(center.x - dynamicRadius * 0.3f, center.y - dynamicRadius * 0.35f),
                                    radius = dynamicRadius * 0.5f
                                ),
                                radius = dynamicRadius * 0.5f,
                                center = Offset(center.x - dynamicRadius * 0.3f, center.y - dynamicRadius * 0.35f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    if (userCaption.isNotBlank()) {
                        Text(
                            text = userCaption,
                            fontSize = 16.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Text(
                        text = jarvisCaption,
                        fontSize = 18.sp,
                        color = Color(0xFF1E293B),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.8f),
                        tonalElevation = 2.dp
                    ) {
                        Text(
                            text = when (voiceState) {
                                VoiceState.LISTENING -> "Listening..."
                                VoiceState.THINKING -> "Thinking..."
                                VoiceState.SPEAKING -> "Speaking..."
                                VoiceState.IDLE -> "Idle"
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            fontSize = 14.sp,
                            color = Color(0xFF334155)
                        )
                    }

                    IconButton(
                        onClick = {
                            isMuted = !isMuted
                            if (isMuted) {
                                stopListeningSession()
                                stopSpeaking()
                                jarvisCaption = "Microphone muted."
                            } else {
                                startListeningSession()
                            }
                        }
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isMuted) Color(0xFFFF5252) else Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (isMuted) "🔇" else "🎙️",
                                    fontSize = 18.sp
                                )
                            }
                        }
                    }
                }

                showConfirmationState?.let { details ->
                    AlertDialog(
                        onDismissRequest = {
                            confirmContinuation?.invoke(false)
                            showConfirmationState = null
                        },
                        title = { Text("Confirm Action") },
                        text = { Text(details) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmContinuation?.invoke(true)
                                showConfirmationState = null
                            }) {
                                Text("Allow")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                confirmContinuation?.invoke(false)
                                showConfirmationState = null
                            }) {
                                Text("Deny")
                            }
                        }
                    )
                }
            }
        }
    }
}
