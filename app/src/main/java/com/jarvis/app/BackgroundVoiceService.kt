package com.jarvis.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class BackgroundVoiceService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val conversationHistory = mutableListOf<JSONObject>()

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isProcessing = false

    private lateinit var prefsManager: PreferencesManager
    private lateinit var geminiClient: GeminiClient
    private lateinit var tools: Tools
    private lateinit var agent: Agent

    private var windowManager: WindowManager? = null
    private var indicatorView: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        prefsManager = PreferencesManager(applicationContext)
        geminiClient = GeminiClient(
            apiKeyProvider = { prefsManager.getApiKey() },
            modelProvider = { prefsManager.getModelName() }
        )

        val confirmHandler: suspend (String) -> Boolean = { details ->
            OverlayConfirmationManager.requestConfirmation(applicationContext, details)
        }

        val termuxBridge = TermuxBridge(applicationContext, confirmHandler)
        tools = Tools(applicationContext, prefsManager, termuxBridge, confirmHandler)
        agent = Agent(geminiClient, tools)

        tts = TextToSpeech(applicationContext, this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showIndicator("Listening...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isProcessing) {
            startListening()
        }
        return START_NOT_STICKY
    }

    private fun showIndicator(text: String) {
        val dm = resources.displayMetrics
        val dp = dm.density

        if (indicatorView == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (48 * dp).toInt()
            }

            indicatorView = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E6202124"))
                    cornerRadius = 20 * dp
                }
                elevation = 16 * dp
            }

            try {
                windowManager?.addView(indicatorView, params)
            } catch (_: Exception) {}
        }
        indicatorView?.text = "● $text"
    }

    private fun startListening() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(this@BackgroundVoiceService)
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
        showIndicator("Listening...")
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim() ?: ""

        if (text.isNotBlank() && !isProcessing) {
            isProcessing = true
            showIndicator("Thinking...")
            serviceScope.launch {
                try {
                    val reply = agent.runTurn(
                        conversationHistory = conversationHistory,
                        userMessage = text,
                        onUpdate = { status -> showIndicator(status) }
                    )
                    showIndicator(reply)
                    speakAndFinish(reply)
                } catch (e: Exception) {
                    val err = if (e.message?.contains("429") == true) {
                        "Rate limit reached. Please wait a minute."
                    } else {
                        "Error: ${e.message}"
                    }
                    showIndicator(err)
                    speakAndFinish(err)
                }
            }
        } else {
            stopSelf()
        }
    }

    private fun speakAndFinish(text: String) {
        if (isTtsReady && tts != null) {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "VOICE_REPLY")
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "VOICE_REPLY")
        } else {
            stopSelf()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isTtsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    serviceScope.launch {
                        stopSelf()
                    }
                }
                override fun onError(utteranceId: String?) {
                    serviceScope.launch {
                        stopSelf()
                    }
                }
            })
        }
    }

    override fun onError(error: Int) {
        stopSelf()
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null

        indicatorView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        indicatorView = null
    }
}
