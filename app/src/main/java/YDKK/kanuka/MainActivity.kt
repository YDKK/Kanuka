package YDKK.kanuka

import YDKK.kanuka.audio.GemmaAudioRecorderManager
import YDKK.kanuka.audio.SpeechRecognizerManager
import YDKK.kanuka.model.AppLanguage
import YDKK.kanuka.model.AppScreen
import YDKK.kanuka.model.ChatMessage
import YDKK.kanuka.model.InputRole
import YDKK.kanuka.model.KanukaUiState
import YDKK.kanuka.model.LiveCardPhase
import YDKK.kanuka.model.LiveTranslationCard
import YDKK.kanuka.model.TranslationMode
import YDKK.kanuka.ui.theme.KanukaTheme
import android.Manifest
import android.R
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val viewModel: KanukaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KanukaApp(viewModel = viewModel)
        }
    }
}

@Composable
private fun KanukaApp(viewModel: KanukaViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingMicRole by remember { mutableStateOf<InputRole?>(null) }
    var pendingGemmaAudioFinalizeRole by remember { mutableStateOf<InputRole?>(null) }
    val mainMessageListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val settingsScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(initial = 0) }
    val speechLanguageScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(initial = 0) }
    val ossLicensesScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(initial = 0) }
    var previousScreen by remember { mutableStateOf(uiState.currentScreen) }

    LaunchedEffect(uiState.currentScreen) {
        val currentScreen = uiState.currentScreen
        if (currentScreen == AppScreen.MAIN && previousScreen == AppScreen.SETTINGS) {
            settingsScrollState.scrollTo(0)
        }
        previousScreen = currentScreen
    }

    LaunchedEffect(Unit) {
        viewModel.setSpeechRecognizerSupportedLanguages(AppLanguage.defaults())
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val requestedRole = pendingMicRole
        pendingMicRole = null

        if (granted && requestedRole != null) {
            viewModel.startListeningFor(requestedRole)
        } else if (!granted) {
            pendingGemmaAudioFinalizeRole = null
            viewModel.stopListening("Microphone permission was denied.")
        }
    }

    val modelFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            persistReadPermission(context = context, uri = uri)
            viewModel.setModelFile(uri.toString())
        }
    }

    val speechRecognizerManager = remember(context) {
        SpeechRecognizerManager(
            context = context,
            onPartialResult = viewModel::onPartialTranscript,
            onFinalResult = viewModel::onFinalTranscript,
            onStatusChanged = viewModel::setStatus
        )
    }
    val gemmaAudioRecorderManager = remember {
        GemmaAudioRecorderManager(
            onStatusChanged = viewModel::setStatus
        )
    }
    var ttsReady by remember { mutableStateOf(false) }
    val textToSpeechHolder = remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context) {
        val tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
        textToSpeechHolder.value = tts
        onDispose {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
            textToSpeechHolder.value = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizerManager.destroy()
            gemmaAudioRecorderManager.destroy()
        }
    }

    LaunchedEffect(
        uiState.isListening,
        uiState.activeInputRole,
        uiState.mode,
        uiState.sourceLanguage,
        uiState.targetLanguage
    ) {
        if (!uiState.isListening) {
            speechRecognizerManager.stopListening()
            val finalizeRole = pendingGemmaAudioFinalizeRole
            if (finalizeRole != null) {
                pendingGemmaAudioFinalizeRole = null
                val audioWavBytes = gemmaAudioRecorderManager.stopRecordingAndBuildWav()
                viewModel.onGemmaAudioCaptured(
                    role = finalizeRole,
                    audioWavBytes = audioWavBytes
                )
            } else {
                gemmaAudioRecorderManager.stopRecording()
            }
            return@LaunchedEffect
        }

        val role = uiState.activeInputRole
        if (role == null) {
            speechRecognizerManager.stopListening()
            gemmaAudioRecorderManager.stopRecording()
            return@LaunchedEffect
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            pendingMicRole = role
            viewModel.stopListening("Microphone permission is required.")
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return@LaunchedEffect
        }

        val listeningLanguage = when (role) {
            InputRole.SOURCE -> uiState.sourceLanguage
            InputRole.TARGET -> uiState.targetLanguage
        }
        if (uiState.mode == TranslationMode.GEMMA_3N_E4B_LITERTLM_DIRECT_AUDIO) {
            speechRecognizerManager.stopListening()
            val started = gemmaAudioRecorderManager.startRecording()
            if (!started) {
                pendingGemmaAudioFinalizeRole = null
                viewModel.stopListening("Gemma direct audio recording failed to start.")
            }
        } else {
            pendingGemmaAudioFinalizeRole = null
            gemmaAudioRecorderManager.stopRecording()
            speechRecognizerManager.startListening(listeningLanguage.localeTag)
        }
    }

    val navigateBack: () -> Unit = {
        when (uiState.currentScreen) {
            AppScreen.MAIN -> Unit
            AppScreen.SETTINGS -> viewModel.closeSettings()
            AppScreen.SPEECH_LANGUAGE_SETTINGS -> viewModel.closeSpeechLanguageSettings()
            AppScreen.OSS_LICENSES -> viewModel.closeOssLicenses()
        }
    }

    val renderScreen: @Composable (AppScreen) -> Unit = { screen ->
        when (screen) {
            AppScreen.MAIN -> MainScreen(
                uiState = uiState,
                messageListState = mainMessageListState,
                onOpenSettings = viewModel::openSettings,
                onClearHistory = viewModel::clearHistory,
                onModeSelected = viewModel::setMode,
                onSourceLanguageSelected = viewModel::setSourceLanguage,
                onTargetLanguageSelected = viewModel::setTargetLanguage,
                onDeleteMessage = viewModel::removeMessage,
                onDeleteLiveCard = viewModel::removeLiveCard,
                onRetranslateLiveCard = viewModel::retranslateLiveCard,
                onRetranslateMessage = viewModel::retranslateMessage,
                onSetConversationContextEnabled = viewModel::setConversationContextEnabled,
                onSpeakTranslatedText = { text, language ->
                    val tts = textToSpeechHolder.value
                    if (!ttsReady || tts == null) {
                        viewModel.setStatus("Text-to-Speech is not ready.")
                        return@MainScreen
                    }
                    val locale = Locale.forLanguageTag(language.localeTag)
                    val availability = tts.isLanguageAvailable(locale)
                    if (availability < TextToSpeech.LANG_AVAILABLE) {
                        viewModel.setStatus("TTS voice for ${language.displayName} is unavailable on this device.")
                        return@MainScreen
                    }
                    tts.language = locale
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
                    viewModel.setStatus("Speaking translation (${language.displayName})...")
                },
                onMicPressForRole = { role ->
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasPermission) {
                        viewModel.startListeningFor(role)
                    } else {
                        pendingMicRole = role
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onMicReleaseForRole = { role ->
                    if (
                        uiState.mode == TranslationMode.GEMMA_3N_E4B_LITERTLM_DIRECT_AUDIO &&
                        uiState.isListening &&
                        uiState.activeInputRole == role
                    ) {
                        pendingGemmaAudioFinalizeRole = role
                    }
                    viewModel.stopListeningForRole(role)
                }
            )

            AppScreen.SETTINGS -> SettingsScreen(
                uiState = uiState,
                scrollState = settingsScrollState,
                onBack = navigateBack,
                onPickModelFile = { modelFileLauncher.launch(arrayOf("*/*")) },
                onClearModelFile = { viewModel.setModelFile(null) },
                onSetDirectAudioSilenceSensitivity = viewModel::setDirectAudioSilenceSensitivity,
                onSetDirectAudioPromptSkipNoSpeech = viewModel::setDirectAudioPromptSkipNoSpeech,
                onSetConversationContextTurns = viewModel::setConversationContextTurns,
                onOpenSpeechLanguageSettings = viewModel::openSpeechLanguageSettings,
                onOpenGoogleSpeechSettings = {
                    openFirstAvailableIntent(
                        context = context,
                        candidates = googleSpeechSettingsIntents(),
                        onFailure = {
                            viewModel.setStatus("Google speech settings screen is not available.")
                        }
                    )
                },
                onOpenTtsSettings = {
                    openFirstAvailableIntent(
                        context = context,
                        candidates = ttsSettingsIntents(),
                        onFailure = {
                            viewModel.setStatus("TTS settings screen is not available.")
                        }
                    )
                },
                onOpenOssLicenses = viewModel::openOssLicenses
            )

            AppScreen.SPEECH_LANGUAGE_SETTINGS -> SpeechLanguageSettingsScreen(
                uiState = uiState,
                scrollState = speechLanguageScrollState,
                onBack = navigateBack,
                onSetLanguageVisible = viewModel::setLanguageVisible
            )

            AppScreen.OSS_LICENSES -> OssLicensesScreen(
                scrollState = ossLicensesScrollState,
                onBack = navigateBack
            )
        }
    }

    KanukaTheme {
        BackHandler(enabled = uiState.currentScreen != AppScreen.MAIN) {
            navigateBack()
        }

        val currentScreen = uiState.currentScreen
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                val initialDepth = screenDepth(initialState)
                val targetDepth = screenDepth(targetState)
                when {
                    targetDepth > initialDepth -> {
                        slideInHorizontally(
                            animationSpec = tween(280),
                            initialOffsetX = { fullWidth -> fullWidth }
                        ) togetherWith slideOutHorizontally(
                            animationSpec = tween(280),
                            targetOffsetX = { fullWidth -> -fullWidth }
                        )
                    }

                    targetDepth < initialDepth -> {
                        slideInHorizontally(
                            animationSpec = tween(280),
                            initialOffsetX = { fullWidth -> -fullWidth }
                        ) togetherWith slideOutHorizontally(
                            animationSpec = tween(280),
                            targetOffsetX = { fullWidth -> fullWidth }
                        )
                    }

                    else -> {
                        slideInHorizontally(
                            animationSpec = tween(280),
                            initialOffsetX = { 0 }
                        ) togetherWith slideOutHorizontally(
                            animationSpec = tween(280),
                            targetOffsetX = { 0 }
                        )
                    }
                }
            },
            label = "screenTransition"
        ) { screen ->
            renderScreen(screen)
        }
    }
}

private fun screenDepth(screen: AppScreen): Int {
    return when (screen) {
        AppScreen.MAIN -> 0
        AppScreen.SETTINGS -> 1
        AppScreen.SPEECH_LANGUAGE_SETTINGS -> 2
        AppScreen.OSS_LICENSES -> 2
    }
}

private fun persistReadPermission(context: android.content.Context, uri: Uri) {
    val contentResolver = context.contentResolver
    try {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    } catch (_: SecurityException) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    uiState: KanukaUiState,
    messageListState: LazyListState,
    onOpenSettings: () -> Unit,
    onClearHistory: () -> Unit,
    onModeSelected: (TranslationMode) -> Unit,
    onSourceLanguageSelected: (AppLanguage) -> Unit,
    onTargetLanguageSelected: (AppLanguage) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onDeleteLiveCard: () -> Unit,
    onRetranslateLiveCard: (String) -> Unit,
    onRetranslateMessage: (Long, String) -> Unit,
    onSetConversationContextEnabled: (Boolean) -> Unit,
    onSpeakTranslatedText: (String, AppLanguage) -> Unit,
    onMicPressForRole: (InputRole) -> Unit,
    onMicReleaseForRole: (InputRole) -> Unit
) {
    var engineMenuExpanded by remember { mutableStateOf(false) }
    var clearAllAnimating by remember { mutableStateOf(false) }
    val newestMessageKey = uiState.messages.firstOrNull()?.let { "${it.id}-${it.timestampMillis}" }
    val liveCardKey = uiState.liveCard?.id
    LaunchedEffect(newestMessageKey, liveCardKey) {
        if (uiState.messages.isNotEmpty() || uiState.liveCard != null) {
            messageListState.animateScrollToItem(0)
        }
    }
    LaunchedEffect(clearAllAnimating) {
        if (clearAllAnimating) {
            delay(BULK_CLEAR_ANIMATION_MS)
            onClearHistory()
            clearAllAnimating = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kanuka Translator") },
                actions = {
                    IconButton(
                        enabled = !clearAllAnimating,
                        onClick = {
                            val hasAnyCard = uiState.messages.isNotEmpty() || uiState.liveCard != null
                            if (!hasAnyCard) {
                                onClearHistory()
                            } else {
                                clearAllAnimating = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear all cards"
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Open settings"
                        )
                    }
                }
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val currentModeLabel = displayTranslationModeLabel(uiState.mode)
                val runtimeBackendLabel = displayRuntimeBackendLabel(uiState.currentRuntimeBackend)
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { engineMenuExpanded = true },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Engine: $currentModeLabel / $runtimeBackendLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select translation engine"
                            )
                        }
                    }
                    Box {
                        DropdownMenu(
                            expanded = engineMenuExpanded,
                            onDismissRequest = { engineMenuExpanded = false }
                        ) {
                            TranslationMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(displayTranslationModeLabel(mode)) },
                                    onClick = {
                                        engineMenuExpanded = false
                                        onModeSelected(mode)
                                    }
                                )
                            }
                        }
                    }
                }

                Text(
                    text = uiState.statusText,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = uiState.conversationContextEnabled,
                                role = Role.Switch,
                                onValueChange = onSetConversationContextEnabled
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Use conversation context",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Recent ${uiState.conversationContextTurns} turn(s) are provided to translation.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.conversationContextEnabled,
                            onCheckedChange = null
                        )
                    }
                }

                if (uiState.isModelLoading) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ModelLoadStatusCard(
                            statusText = uiState.modelLoadStatusText.ifBlank { uiState.statusText },
                            progressPercent = uiState.modelLoadProgressPercent
                        )
                    }
                }

                val liveCard = uiState.liveCard
                if (uiState.messages.isEmpty() && liveCard == null) {
                    EmptyHistory(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            state = messageListState,
                            reverseLayout = true,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (liveCard != null) {
                                item(key = "live-${liveCard.id}") {
                                    BulkRemoveAnimatedContainer(isRemoving = clearAllAnimating) {
                                        SwipeToDeleteContainer(onDelete = onDeleteLiveCard) {
                                            LiveMessageCard(
                                                card = liveCard,
                                                onRetranslate = onRetranslateLiveCard,
                                                onSpeakTranslatedText = onSpeakTranslatedText
                                            )
                                        }
                                    }
                                }
                            }
                            items(
                                items = uiState.messages,
                                key = { message -> "msg-${message.id}-${message.timestampMillis}" }
                            ) { message ->
                                BulkRemoveAnimatedContainer(isRemoving = clearAllAnimating) {
                                    SwipeToDeleteContainer(
                                        onDelete = { onDeleteMessage(message.id) }
                                    ) {
                                        MessageCard(
                                            message = message,
                                            onSpeakTranslatedText = onSpeakTranslatedText,
                                            onRetranslate = { edited ->
                                                onRetranslateMessage(message.id, edited)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .simpleVerticalScrollbar(listState = messageListState, reverseLayout = true)
                        )
                    }
                }

                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    PushToTalkRow(
                        sourceLanguage = uiState.sourceLanguage,
                        targetLanguage = uiState.targetLanguage,
                        supportedLanguages = uiState.speechRecognizerSupportedLanguages,
                        isSourceListening = uiState.isListening && uiState.activeInputRole == InputRole.SOURCE,
                        isTargetListening = uiState.isListening && uiState.activeInputRole == InputRole.TARGET,
                        onSourcePress = { onMicPressForRole(InputRole.SOURCE) },
                        onSourceRelease = { onMicReleaseForRole(InputRole.SOURCE) },
                        onTargetPress = { onMicPressForRole(InputRole.TARGET) },
                        onTargetRelease = { onMicReleaseForRole(InputRole.TARGET) },
                        onSourceLanguageSelected = onSourceLanguageSelected,
                        onTargetLanguageSelected = onTargetLanguageSelected,
                        selectableLanguageTags = uiState.visibleLanguageTags
                    )
                }
            }
        }
    }
}

@Composable
private fun BulkRemoveAnimatedContainer(
    isRemoving: Boolean,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = !isRemoving,
        exit = shrinkVertically(
            animationSpec = tween(BULK_CLEAR_ANIMATION_MS.toInt())
        ) + fadeOut(
            animationSpec = tween(BULK_CLEAR_ANIMATION_MS.toInt())
        )
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteContainer(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var hapticArmed by remember { mutableStateOf(false) }
    var isRemoving by remember { mutableStateOf(false) }
    LaunchedEffect(isRemoving) {
        if (isRemoving) {
            delay(SWIPE_DELETE_ANIMATION_MS)
            onDelete()
        }
    }
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * 0.35f },
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled && !isRemoving) {
                isRemoving = true
            }
            true
        }
    )
    val thresholdReached = dismissState.targetValue != SwipeToDismissBoxValue.Settled
    LaunchedEffect(thresholdReached) {
        if (thresholdReached && !hapticArmed) {
            hapticArmed = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        } else if (!thresholdReached && dismissState.currentValue == SwipeToDismissBoxValue.Settled) {
            hapticArmed = false
        }
    }
    val backgroundColor by animateColorAsState(
        targetValue = if (thresholdReached) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        label = "swipeDeleteBackground"
    )
    val contentColor = if (thresholdReached) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    AnimatedVisibility(
        visible = !isRemoving,
        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
    ) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = true,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                val direction = dismissState.dismissDirection
                val isStartToEnd = direction == SwipeToDismissBoxValue.StartToEnd
                val isEndToStart = direction == SwipeToDismissBoxValue.EndToStart
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    contentAlignment = when {
                        isStartToEnd -> Alignment.CenterStart
                        isEndToStart -> Alignment.CenterEnd
                        else -> Alignment.Center
                    }
                ) {
                    Surface(
                        color = backgroundColor,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = contentColor
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (thresholdReached) {
                                    "Release to delete"
                                } else {
                                    "Swipe to delete"
                                },
                                color = contentColor,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        ) {
            content()
        }
    }
}

private const val SWIPE_DELETE_ANIMATION_MS = 200L
private const val BULK_CLEAR_ANIMATION_MS = 220L
private const val KANUKA_REPO_URL = "https://github.com/YDKK/Kanuka"

private fun googleSpeechSettingsIntents(): List<Intent> {
    return listOf(
        Intent("android.settings.INPUT_METHOD_SETTINGS"),
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
    )
}

private fun ttsSettingsIntents(): List<Intent> {
    return listOf(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )
}

private fun openFirstAvailableIntent(
    context: android.content.Context,
    candidates: List<Intent>,
    onFailure: () -> Unit
) {
    for (candidate in candidates) {
        try {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(candidate)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    onFailure()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OssLicensesScreen(
    scrollState: ScrollState,
    onBack: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OSS Licenses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OSSLicenses.entries.forEach { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = item.library,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "License: ${item.license}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = item.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    uriHandler.openUri(item.url)
                                }
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .simpleVerticalScrollbar(scrollState)
            )
        }
    }
}

private data class OSSLicensesItem(
    val library: String,
    val license: String,
    val url: String
)

private enum class OSSLicenses(
    val library: String,
    val license: String,
    val url: String
) {
    KOTLIN(
        library = "Kotlin Standard Library",
        license = "Apache License 2.0",
        url = "https://github.com/JetBrains/kotlin"
    ),
    KOTLINX_COROUTINES(
        library = "kotlinx.coroutines",
        license = "Apache License 2.0",
        url = "https://github.com/Kotlin/kotlinx.coroutines"
    ),
    ANDROIDX_CORE(
        library = "AndroidX Core KTX",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx"
    ),
    ANDROIDX_ACTIVITY_COMPOSE(
        library = "AndroidX Activity Compose",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx"
    ),
    ANDROIDX_LIFECYCLE(
        library = "AndroidX Lifecycle",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx"
    ),
    JETPACK_COMPOSE_UI(
        library = "Jetpack Compose UI / Material3",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/compose"
    ),
    ANDROIDX_DOCUMENTFILE(
        library = "AndroidX DocumentFile",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx"
    ),
    GOOGLE_MATERIAL(
        library = "Material Components for Android",
        license = "Apache License 2.0",
        url = "https://github.com/material-components/material-components-android"
    ),
    LITERT_LM_ANDROID(
        library = "LiteRT-LM Android",
        license = "Apache License 2.0",
        url = "https://github.com/google-ai-edge/LiteRT"
    )
}

@Composable
private fun PushToTalkLanguageControl(
    modifier: Modifier = Modifier,
    language: AppLanguage,
    supportedLanguages: List<AppLanguage>,
    selectableLanguageTags: Set<String>,
    isActive: Boolean,
    dropdownContentDescription: String,
    onLanguageSelected: (AppLanguage) -> Unit,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var wasPressed by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (isPressed && !wasPressed) {
            wasPressed = true
            onPress()
        } else if (!isPressed && wasPressed) {
            wasPressed = false
            onRelease()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (wasPressed) {
                onRelease()
            }
        }
    }

    val active = isActive || isPressed

    Box(modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(10.dp, 12.dp),
                    onClick = {},
                    interactionSource = interactionSource,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = if (active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ){
                        Icon(
                            imageVector = if (active) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null,
                        )
                        Text(
                            text = if (active) {
                                "Listening\n${language.displayName}"
                            } else {
                                "Hold\n${language.displayName}"
                            },
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = dropdownContentDescription
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        val options = supportedLanguages.filter { option ->
                            selectableLanguageTags.contains(option.localeTag) ||
                                option.localeTag.equals(language.localeTag, ignoreCase = true)
                        }
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.displayName) },
                                onClick = {
                                    expanded = false
                                    onLanguageSelected(option)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PushToTalkRow(
    sourceLanguage: AppLanguage,
    targetLanguage: AppLanguage,
    supportedLanguages: List<AppLanguage>,
    isSourceListening: Boolean,
    isTargetListening: Boolean,
    onSourcePress: () -> Unit,
    onSourceRelease: () -> Unit,
    onTargetPress: () -> Unit,
    onTargetRelease: () -> Unit,
    onSourceLanguageSelected: (AppLanguage) -> Unit,
    onTargetLanguageSelected: (AppLanguage) -> Unit,
    selectableLanguageTags: Set<String>
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PushToTalkLanguageControl(
            modifier = Modifier.weight(1f),
            language = sourceLanguage,
            supportedLanguages = supportedLanguages,
            selectableLanguageTags = selectableLanguageTags,
            isActive = isSourceListening,
            dropdownContentDescription = "Select source language",
            onLanguageSelected = onSourceLanguageSelected,
            onPress = onSourcePress,
            onRelease = onSourceRelease
        )
        PushToTalkLanguageControl(
            modifier = Modifier.weight(1f),
            language = targetLanguage,
            supportedLanguages = supportedLanguages,
            selectableLanguageTags = selectableLanguageTags,
            isActive = isTargetListening,
            dropdownContentDescription = "Select target language",
            onLanguageSelected = onTargetLanguageSelected,
            onPress = onTargetPress,
            onRelease = onTargetRelease
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    uiState: KanukaUiState,
    scrollState: ScrollState,
    onBack: () -> Unit,
    onPickModelFile: () -> Unit,
    onClearModelFile: () -> Unit,
    onSetDirectAudioSilenceSensitivity: (Int) -> Unit,
    onSetDirectAudioPromptSkipNoSpeech: (Boolean) -> Unit,
    onSetConversationContextTurns: (Int) -> Unit,
    onOpenSpeechLanguageSettings: () -> Unit,
    onOpenGoogleSpeechSettings: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onOpenOssLicenses: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val versionLabel = remember {
        runCatching {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0L)
            )
            "v${packageInfo.versionName ?: "unknown"} (${packageInfo.longVersionCode})"
        }.getOrDefault("Version unknown")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            Text(
                text = "Runtime Backend",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = displayRuntimeBackendLabel(uiState.currentRuntimeBackend),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text(
                text = "Model Variant",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Gemma 3n E4B",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text(
                text = "Model File",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Select gemma-3n-E4B-it-int4.litertlm",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.modelFileUri ?: "Not selected",
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onPickModelFile) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Select",
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                        OutlinedButton(onClick = onClearModelFile) {
                            Text("Clear")
                        }
                    }
                }
            }

            Text(
                text = "Direct Audio",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Silence detection sensitivity: ${uiState.directAudioSilenceSensitivity}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = uiState.directAudioSilenceSensitivity.toFloat(),
                        onValueChange = { value ->
                            onSetDirectAudioSilenceSensitivity(value.toInt())
                        },
                        valueRange = 0f..100f
                    )
                    Text(
                        text = "Higher value detects silence more aggressively.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = uiState.directAudioPromptSkipNoSpeech,
                                role = Role.Switch,
                                onValueChange = onSetDirectAudioPromptSkipNoSpeech
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Skip quickly when no speech is heard",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Prompt model to return immediately without translation if audio is unintelligible.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.directAudioPromptSkipNoSpeech,
                            onCheckedChange = null
                        )
                    }
                }
            }

            Text(
                text = "SpeechRecognizer Languages",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Loaded: ${uiState.speechRecognizerSupportedLanguages.size} languages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinkActionRow(
                        text = "Open language visibility settings",
                        onClick = onOpenSpeechLanguageSettings
                    )
                }
            }

            Text(
                text = "Conversation Context",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Turns to pass: ${uiState.conversationContextTurns}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = uiState.conversationContextTurns.toFloat(),
                        onValueChange = { value ->
                            onSetConversationContextTurns(value.toInt())
                        },
                        valueRange = 1f..10f
                    )
                }
            }

            Text(
                text = "Speech / TTS Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinkActionRow(
                        text = "Open Google speech input settings",
                        onClick = onOpenGoogleSpeechSettings
                    )
                    LinkActionRow(
                        text = "Open TTS voice settings",
                        onClick = onOpenTtsSettings
                    )
                }
            }

            Text(
                text = "Open Source Licenses",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    LinkActionRow(
                        text = "View OSS licenses",
                        onClick = onOpenOssLicenses
                    )
                }
            }

            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)){
                        Icon(
                            painter = painterResource(YDKK.kanuka.R.drawable.ic_logo),
                            contentDescription = "Kanuka Translator by YDKK"
                        )
                        Text(
                            text = "Kanuka Translator by YDKK",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "Version: $versionLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinkActionRow(
                        text = "GitHub",
                        onClick = { uriHandler.openUri(KANUKA_REPO_URL) }
                    )
                }
            }

            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .simpleVerticalScrollbar(scrollState)
            )
        }
    }
}

@Composable
private fun LinkActionRow(
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeechLanguageSettingsScreen(
    uiState: KanukaUiState,
    scrollState: ScrollState,
    onBack: () -> Unit,
    onSetLanguageVisible: (String, Boolean) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SpeechRecognizer Languages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Select languages shown in source/target dropdowns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                uiState.speechRecognizerSupportedLanguages.forEach { language ->
                    val locked = language.localeTag.equals(uiState.sourceLanguage.localeTag, ignoreCase = true) ||
                        language.localeTag.equals(uiState.targetLanguage.localeTag, ignoreCase = true)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = uiState.visibleLanguageTags.contains(language.localeTag),
                                    enabled = !locked,
                                    role = Role.Checkbox,
                                    onValueChange = { checked ->
                                        onSetLanguageVisible(language.localeTag, checked)
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = language.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = language.localeTag,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Checkbox(
                                checked = uiState.visibleLanguageTags.contains(language.localeTag),
                                enabled = !locked,
                                onCheckedChange = null
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .simpleVerticalScrollbar(scrollState)
            )
        }
    }
}

@Composable
private fun Modifier.simpleVerticalScrollbar(
    scrollState: ScrollState
): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (scrollState.isScrollInProgress) 1f else 0f,
        label = "scrollbarAlpha"
    )
    return this.drawWithContent {
        drawContent()
        if (scrollState.maxValue <= 0 || alpha <= 0.01f) return@drawWithContent

        val thumbWidth = 3.dp.toPx()
        val thumbX = size.width - thumbWidth
        val viewport = size.height
        val contentHeight = viewport + scrollState.maxValue.toFloat()
        val visibleRatio = (viewport / contentHeight).coerceIn(0.08f, 1f)
        val thumbHeight = (viewport * visibleRatio).coerceAtLeast(24.dp.toPx())
        val progress = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
        val thumbY = (viewport - thumbHeight) * progress

        drawRoundRect(
            color = Color.White.copy(alpha = 0.85f * alpha),
            topLeft = androidx.compose.ui.geometry.Offset(thumbX, thumbY),
            size = androidx.compose.ui.geometry.Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbWidth, thumbWidth)
        )
    }
}

@Composable
private fun Modifier.simpleVerticalScrollbar(
    listState: LazyListState,
    reverseLayout: Boolean = false
): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (listState.isScrollInProgress) 1f else 0f,
        label = "lazyScrollbarAlpha"
    )
    return this.drawWithContent {
        drawContent()

        val layoutInfo = listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty() || alpha <= 0.01f) return@drawWithContent

        val viewportHeight = size.height
        if (viewportHeight <= 0f) return@drawWithContent

        val totalItems = layoutInfo.totalItemsCount
        if (totalItems <= 0) return@drawWithContent

        val averageItemSize = visibleItems.map { it.size }.average().toFloat().coerceAtLeast(1f)
        val estimatedContentHeight = (averageItemSize * totalItems)
        if (estimatedContentHeight <= viewportHeight + 1f) return@drawWithContent

        val firstVisible = visibleItems.first()
        val estimatedScrollPx =
            (firstVisible.index * averageItemSize - firstVisible.offset).coerceAtLeast(0f)
        val maxScrollPx = (estimatedContentHeight - viewportHeight).coerceAtLeast(1f)
        var progress = (estimatedScrollPx / maxScrollPx).coerceIn(0f, 1f)
        if (reverseLayout) {
            progress = 1f - progress
        }

        val thumbWidth = 3.dp.toPx()
        val thumbX = size.width - thumbWidth
        val visibleRatio = (viewportHeight / estimatedContentHeight).coerceIn(0.08f, 1f)
        val thumbHeight = (viewportHeight * visibleRatio).coerceAtLeast(24.dp.toPx())
        val thumbY = (viewportHeight - thumbHeight) * progress

        drawRoundRect(
            color = Color.White.copy(alpha = 0.85f * alpha),
            topLeft = androidx.compose.ui.geometry.Offset(thumbX, thumbY),
            size = androidx.compose.ui.geometry.Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbWidth, thumbWidth)
        )
    }
}

@Preview(name = "Main Screen", showBackground = true, widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MainScreenPreview() {
    val sampleMessages = listOf(
        ChatMessage(
            id = 1L,
            mode = TranslationMode.GEMMA_3N_E4B_LITERTLM_SPEECH_RECOGNIZER,
            sourceLanguage = AppLanguage.JAPANESE,
            targetLanguage = AppLanguage.ENGLISH,
            sourceText = "今日は良い天気ですね。",
            translatedText = "It's nice weather today."
        ),
        ChatMessage(
            id = 2L,
            mode = TranslationMode.GEMMA_3N_E4B_LITERTLM_SPEECH_RECOGNIZER,
            sourceLanguage = AppLanguage.ENGLISH,
            targetLanguage = AppLanguage.JAPANESE,
            sourceText = "Can we start now?",
            translatedText = "今始められますか？"
        ),
        ChatMessage(
            id = 3L,
            mode = TranslationMode.GEMMA_3N_E4B_LITERTLM_SPEECH_RECOGNIZER,
            sourceLanguage = AppLanguage.JAPANESE,
            targetLanguage = AppLanguage.ENGLISH,
            sourceText = "今日は良い天気ですね。",
            translatedText = "It's nice weather today."
        ),
        ChatMessage(
            id = 4L,
            mode = TranslationMode.GEMMA_3N_E4B_LITERTLM_SPEECH_RECOGNIZER,
            sourceLanguage = AppLanguage.ENGLISH,
            targetLanguage = AppLanguage.JAPANESE,
            sourceText = "Can we start now?",
            translatedText = "今始められますか？"
        )
    )

    KanukaTheme {
        MainScreen(
            uiState = KanukaUiState(
                modelFileUri = "content://preview/model/gemma-3n-E4B-it-int4.litertlm",
                statusText = "Ready.",
                messages = sampleMessages,
                speechRecognizerSupportedLanguages = AppLanguage.defaults(),
                visibleLanguageTags = AppLanguage.defaults().map { it.localeTag }.toSet()
            ),
            messageListState = LazyListState(),
            onOpenSettings = {},
            onClearHistory = {},
            onModeSelected = {},
            onSourceLanguageSelected = {},
            onTargetLanguageSelected = {},
            onDeleteMessage = {},
            onDeleteLiveCard = {},
            onRetranslateLiveCard = {},
            onRetranslateMessage = { _, _ -> },
            onSetConversationContextEnabled = {},
            onSpeakTranslatedText = { _, _ -> },
            onMicPressForRole = {},
            onMicReleaseForRole = {}
        )
    }
}

@Preview(name = "Settings Screen", showBackground = true, widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsScreenPreview() {
    KanukaTheme {
        SettingsScreen(
            uiState = KanukaUiState(
                currentScreen = AppScreen.SETTINGS,
                modelFileUri = "content://preview/model/gemma-3n-E4B-it-int4.litertlm",
                statusText = "Model file configured.",
                speechRecognizerSupportedLanguages = AppLanguage.defaults(),
                visibleLanguageTags = AppLanguage.defaults().map { it.localeTag }.toSet()
            ),
            scrollState = ScrollState(initial = 0),
            onBack = {},
            onPickModelFile = {},
            onClearModelFile = {},
            onSetDirectAudioSilenceSensitivity = {},
            onSetDirectAudioPromptSkipNoSpeech = {},
            onSetConversationContextTurns = {},
            onOpenSpeechLanguageSettings = {},
            onOpenGoogleSpeechSettings = {},
            onOpenTtsSettings = {},
            onOpenOssLicenses = {}
        )
    }
}

private fun displayTranslationModeLabel(mode: TranslationMode): String {
    return mode.title.replace("LiteRT-LM NPU", "LiteRT-LM")
}

private fun displayRuntimeBackendLabel(raw: String): String {
    val normalized = raw.ifBlank { "Not initialized" }
    if (normalized.equals("Not initialized", ignoreCase = true)) {
        return "Not initialized"
    }
    val hasGpu = normalized.contains("GPU", ignoreCase = true)
    val hasCpu = normalized.contains("CPU", ignoreCase = true)
    return when {
        hasGpu && hasCpu -> "GPU + CPU"
        hasGpu -> "GPU"
        hasCpu -> "CPU"
        else -> normalized.replace("NPU", "Accelerated", ignoreCase = true)
    }
}

private fun displayRuntimeNameLabel(raw: String): String {
    return raw.replace("NPU", "Accelerated", ignoreCase = true)
}

@Composable
private fun ModelLoadStatusCard(
    statusText: String,
    progressPercent: Int?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (progressPercent != null) {
                LinearProgressIndicator(
                    progress = { (progressPercent.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "$progressPercent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LiveMessageCard(
    card: LiveTranslationCard,
    onRetranslate: (String) -> Unit,
    onSpeakTranslatedText: (String, AppLanguage) -> Unit
) {
    var editableSourceText by remember(card.id) {
        mutableStateOf(card.sourceText)
    }
    var userEditedSourceText by remember(card.id) { mutableStateOf(false) }
    LaunchedEffect(card.sourceText) {
        if (!userEditedSourceText || editableSourceText.isBlank()) {
            editableSourceText = card.sourceText
        }
    }
    val phaseText = when (card.phase) {
        LiveCardPhase.RECOGNIZING -> "Recognizing"
        LiveCardPhase.RECOGNIZED -> "Recognized"
        LiveCardPhase.TRANSLATING -> "Translating"
    }
    val showListeningLabel = card.sourceText.isBlank() && (
        card.phase == LiveCardPhase.RECOGNIZING || card.phase == LiveCardPhase.TRANSLATING
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = phaseText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (showListeningLabel) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(
                            text = if (card.phase == LiveCardPhase.RECOGNIZING) {
                                "Listening with Gemma 3n..."
                            } else {
                                "Recognizing speech from audio..."
                            }
                        )
                    }
                )
            }
            Text(
                text = "Recognized (${card.sourceLanguage.displayName})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                BasicTextField(
                    value = editableSourceText,
                    onValueChange = {
                        editableSourceText = it
                        userEditedSourceText = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 8.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                IconButton(
                    enabled = editableSourceText.trim().isNotEmpty(),
                    onClick = {
                        userEditedSourceText = false
                        onRetranslate(editableSourceText)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retranslate recognized text"
                    )
                }
            }
            if (card.phase == LiveCardPhase.TRANSLATING || card.translatedText.isNotBlank()) {
                Text(
                    text = "Translation (${card.targetLanguage.displayName})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val bodyText = card.translatedText.ifBlank { "..." }
                val style = MaterialTheme.typography.headlineSmall
                val weight = if (card.phase == LiveCardPhase.TRANSLATING) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Bold
                }
                SelectionContainer {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = bodyText,
                            style = style,
                            fontWeight = weight,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            enabled = card.translatedText.isNotBlank() && card.phase != LiveCardPhase.TRANSLATING,
                            onClick = {
                                onSpeakTranslatedText(card.translatedText, card.targetLanguage)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Speak translation"
                            )
                        }
                    }
                }
            }
            if (card.phase == LiveCardPhase.RECOGNIZED && card.translatedText.isBlank()) {
                Text(
                    text = "Ready to translate...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "In progress",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PartialTranscriptCard(
    text: String,
    sourceLanguage: AppLanguage,
    isListening: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (isListening) {
                    "Recognizing (${sourceLanguage.displayName})"
                } else {
                    "Recognized (${sourceLanguage.displayName})"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun MessageCard(
    message: ChatMessage,
    onSpeakTranslatedText: (String, AppLanguage) -> Unit,
    onRetranslate: (String) -> Unit
) {
    val initialSourceText = message.sourceText.orEmpty()
    var editableSourceText by remember(message.id, message.timestampMillis, initialSourceText) {
        mutableStateOf(initialSourceText)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            message.sourceText?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "Recognized (${message.sourceLanguage.displayName})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BasicTextField(
                        value = editableSourceText,
                        onValueChange = { editableSourceText = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    IconButton(
                        enabled = editableSourceText.trim().isNotEmpty(),
                        onClick = { onRetranslate(editableSourceText) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retranslate recognized text"
                        )
                    }
                }
            }

            Text(
                text = "Translation (${message.targetLanguage.displayName})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SelectionContainer {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = message.translatedText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        enabled = message.translatedText.isNotBlank(),
                        onClick = {
                            onSpeakTranslatedText(message.translatedText, message.targetLanguage)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Speak translation"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No translations yet. Hold a language button at the bottom to speak.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
