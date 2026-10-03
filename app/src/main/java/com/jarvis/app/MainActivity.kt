package com.jarvis.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext

data class ChatMessage(val sender: String, val text: String)

data class InstalledAppItem(
    val packageName: String,
    val appName: String,
    var isAllowed: Boolean
)

data class ModelOption(
    val label: String,
    val modelId: String
)

class MainActivity : ComponentActivity() {

    private lateinit var prefsManager: PreferencesManager
    private lateinit var geminiClient: GeminiClient
    private lateinit var tools: Tools
    private lateinit var agent: Agent

    private val conversationHistory = mutableListOf<JSONObject>()

    companion object {
        val SUPPORTED_MODELS = listOf(
            ModelOption("Gemini 3.8 Flash", "gemini-3.8-flash"),
            ModelOption("Gemini 3.7 Flash", "gemini-3.7-flash"),
            ModelOption("Gemini 3.6 Flash", "gemini-3.6-flash"),
            ModelOption("Gemini 3.5 Flash", "gemini-3.5-flash"),
            ModelOption("Gemini 3.5 Flash Lite", "gemini-3.5-flash-lite"),
            ModelOption("Gemini 3.1 Flash Lite", "gemini-3.1-flash-lite"),
            ModelOption("Gemini 3 Flash Preview", "gemini-3-flash-preview"),
            ModelOption("Gemini Flash Latest", "gemini-flash-latest"),
            ModelOption("Gemini Flash-Lite Latest", "gemini-flash-lite-latest")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisLogger.init(this)

        prefsManager = PreferencesManager(applicationContext)
        geminiClient = GeminiClient(
            apiKeyProvider = { prefsManager.getApiKey() },
            modelProvider = { prefsManager.getModelName() }
        )

        val confirmHandler: suspend (String) -> Boolean = { details ->
            OverlayConfirmationManager.requestConfirmation(applicationContext, details)
        }

        val termuxBridge = TermuxBridge(
            context = applicationContext,
            confirmCallback = confirmHandler
        )

        tools = Tools(
            context = applicationContext,
            prefsManager = prefsManager,
            termuxBridge = termuxBridge,
            confirmCallback = confirmHandler
        )

        agent = Agent(geminiClient, tools)

        setContent {
            JarvisMainScreen()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun JarvisMainScreen() {
        var selectedTab by remember { mutableIntStateOf(0) }
        val tabs = listOf("Chat", "Voice", "Apps", "Settings", "Logs")

        MaterialTheme {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        tabs.forEachIndexed { index, title ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                label = { Text(title) },
                                icon = { }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                    when (selectedTab) {
                        0 -> ChatTabScreen()
                        1 -> InAppVoiceTabScreen()
                        2 -> AppsTabScreen()
                        3 -> SettingsTabScreen()
                        4 -> LogsTabScreen()
                    }
                }
            }
        }
    }

    @Composable
    fun InAppVoiceTabScreen() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(140.dp)
                        .clickable {
                            val intent = Intent(this@MainActivity, BackgroundVoiceService::class.java)
                            startService(intent)
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("🎙️", fontSize = 42.sp)
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Tap to talk to Jarvis",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Jarvis runs in the background over whatever app you are using.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    @Composable
    fun ChatTabScreen() {
        val messages = remember { mutableStateListOf<ChatMessage>() }
        var inputText by remember { mutableStateOf("") }
        var isBusy by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()

        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true
            ) {
                items(messages.reversed()) { msg ->
                    val isUser = msg.sender == "Me"
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = msg.text,
                                modifier = Modifier.padding(10.dp),
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask Jarvis...") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !isBusy
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val text = inputText.trim()
                        if (text.isNotEmpty() && !isBusy) {
                            inputText = ""
                            messages.add(ChatMessage("Me", text))
                            isBusy = true

                            coroutineScope.launch {
                                try {
                                    val reply = agent.runTurn(
                                        conversationHistory = conversationHistory,
                                        userMessage = text,
                                        onUpdate = { _ -> }
                                    )
                                    messages.add(ChatMessage("Jarvis", reply))
                                } catch (e: Exception) {
                                    messages.add(ChatMessage("Jarvis", "Error: ${e.message}"))
                                } finally {
                                    isBusy = false
                                }
                            }
                        }
                    },
                    enabled = !isBusy && inputText.isNotBlank()
                ) {
                    Text("Send")
                }
            }
        }
    }

    @Composable
    fun AppsTabScreen() {
        var installedApps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val activities = pm.queryIntentActivities(intent, 0)
                val allowedSet = prefsManager.getAllowedPackages()

                val list = activities.mapNotNull { resolveInfo ->
                    val pkg = resolveInfo.activityInfo.packageName
                    if (pkg == packageName) return@mapNotNull null
                    val name = resolveInfo.loadLabel(pm).toString()
                    InstalledAppItem(pkg, name, allowedSet.contains(pkg))
                }.sortedBy { it.appName }

                installedApps = list
                isLoading = false
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                items(installedApps, key = { it.packageName }) { appItem ->
                    var isChecked by remember { mutableStateOf(appItem.isAllowed) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = appItem.appName, fontSize = 16.sp)
                            Text(text = appItem.packageName, fontSize = 12.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                isChecked = checked
                                appItem.isAllowed = checked
                                prefsManager.setPackageAllowed(appItem.packageName, checked)
                            }
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsTabScreen() {
        var apiKey by remember { mutableStateOf(prefsManager.getApiKey()) }
        var selectedModelId by remember { mutableStateOf(prefsManager.getModelName()) }
        var isSaved by remember { mutableStateOf(false) }
        var expanded by remember { mutableStateOf(false) }

        val currentLabel = SUPPORTED_MODELS.firstOrNull { it.modelId == selectedModelId }?.label
            ?: selectedModelId

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    isSaved = false
                },
                label = { Text("Gemini API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Select Gemini Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    SUPPORTED_MODELS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                selectedModelId = option.modelId
                                expanded = false
                                isSaved = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    prefsManager.setApiKey(apiKey)
                    prefsManager.setModelName(selectedModelId)
                    isSaved = true
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Save Settings")
            }

            if (isSaved) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Settings saved successfully.", color = MaterialTheme.colorScheme.primary)
            }
        }
    }


    @Composable
    fun LogsTabScreen() {
        val ctx = LocalContext.current
        var text by remember { mutableStateOf(JarvisLogger.dump()) }

        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Trace (last request)", style = MaterialTheme.typography.titleMedium,
                     modifier = Modifier.weight(1f))
                TextButton(onClick = { text = JarvisLogger.dump() }) { Text("Refresh") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("JarvisTrace", text))
                }) { Text("Copy all") }
                TextButton(onClick = { JarvisLogger.clear(); text = JarvisLogger.dump() }) {
                    Text("Clear")
                }
            }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                val scroll = rememberScrollState()
                Box(modifier = Modifier.fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(8.dp))
                {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
    }

}
