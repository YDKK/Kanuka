package YDKK.kanuka

import YDKK.kanuka.data.KanukaPreferences
import YDKK.kanuka.engine.ModelPreloadOutput
import YDKK.kanuka.engine.TranslationEngineFactory
import YDKK.kanuka.engine.TranslationOutput
import YDKK.kanuka.engine.TranslationRequest
import YDKK.kanuka.model.AppLanguage
import YDKK.kanuka.model.AppScreen
import YDKK.kanuka.model.ChatMessage
import YDKK.kanuka.model.ConversationContextTurn
import YDKK.kanuka.model.InputRole
import YDKK.kanuka.model.KanukaUiState
import YDKK.kanuka.model.LiveCardPhase
import YDKK.kanuka.model.LiveTranslationCard
import YDKK.kanuka.model.TranslationMode
import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class KanukaViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = KanukaPreferences(application)
    private val engineFactory = TranslationEngineFactory(application.applicationContext)
    private val messageIdCounter = AtomicLong(0L)
    private val translationTokenCounter = AtomicLong(0L)

    private val _uiState = MutableStateFlow(KanukaUiState())
    val uiState: StateFlow<KanukaUiState> = _uiState.asStateFlow()

    private var lastFinalTranscript: String = ""
    private var lastFinalTimestamp: Long = 0L
    private var latestRecognitionRole: InputRole? = null
    private var stopFinalizedFingerprint: String? = null
    private var stopFinalizedAtMillis: Long = 0L
    private var lastStreamingUiUpdateAtMillis: Long = 0L
    private var lastStreamingUiText: String = ""
    private var currentListeningSessionId: Long = 0L
    private var lastSubmittedFingerprint: String? = null
    private var lastSubmittedSessionId: Long = -1L
    private val listeningFinalSegments = mutableListOf<String>()
    private var listeningLatestPartial: String = ""
    private var suppressPostStopRecognizerFinalSubmission: Boolean = false
    private var modelPreloadJob: Job? = null
    private var modelPreloadInFlightSignature: String? = null
    private var lastSuccessfulModelPreloadSignature: String? = null
    private var translationJob: Job? = null
    private var activeTranslationMode: TranslationMode? = null
    private var activeTranslationMessageId: Long? = null
    private var activeTranslationToken: Long? = null
    private val pendingTranslationQueue = ArrayDeque<QueuedTranslationWork>()
    private var pendingStopFinalizeJob: Job? = null

    init {
        val saved = preferences.loadConfig()
        _uiState.update {
            it.copy(
                mode = saved.mode,
                sourceLanguage = saved.sourceLanguage,
                targetLanguage = if (saved.sourceLanguage == saved.targetLanguage) {
                    fallbackTarget(saved.sourceLanguage)
                } else {
                    saved.targetLanguage
                },
                modelFileUri = saved.modelFileUri,
                directAudioSilenceSensitivity = saved.directAudioSilenceSensitivity,
                directAudioPromptSkipNoSpeech = saved.directAudioPromptSkipNoSpeech,
                speechRecognizerSupportedLanguages = saved.speechRecognizerSupportedLanguages,
                visibleLanguageTags = saved.visibleLanguageTags.ifEmpty {
                    saved.speechRecognizerSupportedLanguages.map { language -> language.localeTag }.toSet()
                },
                conversationContextEnabled = saved.conversationContextEnabled,
                conversationContextTurns = saved.conversationContextTurns,
                statusText = if (saved.modelFileUri.isNullOrBlank()) {
                    "Set model file in Settings before starting microphone."
                } else {
                    "Ready."
                }
            )
        }
        triggerAutoModelPreload()
    }

    fun openSettings() {
        _uiState.update { it.copy(currentScreen = AppScreen.SETTINGS) }
    }

    fun openOssLicenses() {
        _uiState.update { it.copy(currentScreen = AppScreen.OSS_LICENSES) }
    }

    fun openSpeechLanguageSettings() {
        _uiState.update { it.copy(currentScreen = AppScreen.SPEECH_LANGUAGE_SETTINGS) }
    }

    fun closeSettings() {
        _uiState.update { it.copy(currentScreen = AppScreen.MAIN) }
        triggerAutoModelPreload()
    }

    fun closeOssLicenses() {
        _uiState.update { it.copy(currentScreen = AppScreen.SETTINGS) }
    }

    fun closeSpeechLanguageSettings() {
        _uiState.update { it.copy(currentScreen = AppScreen.SETTINGS) }
    }

    fun setSpeechRecognizerSupportedLanguages(languages: List<AppLanguage>) {
        val normalized = languages
            .distinctBy { it.localeTag.lowercase() }
            .ifEmpty { AppLanguage.defaults() }

        _uiState.update { current ->
            val knownTags = normalized.map { it.localeTag }.toSet()
            val source = normalized.firstOrNull {
                it.localeTag.equals(current.sourceLanguage.localeTag, ignoreCase = true)
            } ?: current.sourceLanguage
            val targetCandidate = normalized.firstOrNull {
                it.localeTag.equals(current.targetLanguage.localeTag, ignoreCase = true)
            } ?: current.targetLanguage
            val target = if (source.localeTag.equals(targetCandidate.localeTag, ignoreCase = true)) {
                fallbackTarget(source, normalized)
            } else {
                targetCandidate
            }
            val visible = current.visibleLanguageTags
                .filter { tag -> knownTags.contains(tag) }
                .toMutableSet()
                .apply {
                    add(source.localeTag)
                    add(target.localeTag)
                    if (isEmpty()) {
                        addAll(knownTags)
                    }
                }
            current.copy(
                speechRecognizerSupportedLanguages = normalized,
                sourceLanguage = source,
                targetLanguage = target,
                visibleLanguageTags = visible
            )
        }
        persist()
    }

    fun setMode(mode: TranslationMode) {
        cancelPendingStopFinalize()
        abortActiveTranslationForNewRequest(clearLiveCard = true)
        clearPendingTranslationQueue()
        _uiState.update {
            it.copy(
                mode = mode,
                currentRuntimeBackend = "Not initialized",
                currentRuntimeName = ""
            )
        }
        persist()
        triggerAutoModelPreload()
    }

    fun setSourceLanguage(language: AppLanguage) {
        _uiState.update { current ->
            val target = if (language.localeTag.equals(current.targetLanguage.localeTag, ignoreCase = true)) {
                fallbackTarget(language)
            } else {
                current.targetLanguage
            }
            current.copy(
                sourceLanguage = language,
                targetLanguage = target,
                visibleLanguageTags = current.visibleLanguageTags + language.localeTag + target.localeTag
            )
        }
        persist()
    }

    fun setTargetLanguage(language: AppLanguage) {
        _uiState.update { current ->
            val source = if (language.localeTag.equals(current.sourceLanguage.localeTag, ignoreCase = true)) {
                fallbackSource(language)
            } else {
                current.sourceLanguage
            }
            current.copy(
                sourceLanguage = source,
                targetLanguage = language,
                visibleLanguageTags = current.visibleLanguageTags + source.localeTag + language.localeTag
            )
        }
        persist()
    }

    fun setModelFile(uriString: String?) {
        _uiState.update {
            it.copy(
                modelFileUri = uriString,
                statusText = if (uriString.isNullOrBlank()) {
                    "Model file cleared."
                } else {
                    "Model file configured."
                }
            )
        }
        persist()
    }

    fun setDirectAudioSilenceSensitivity(value: Int) {
        _uiState.update { current ->
            current.copy(directAudioSilenceSensitivity = value.coerceIn(0, 100))
        }
        persist()
    }

    fun setDirectAudioPromptSkipNoSpeech(enabled: Boolean) {
        _uiState.update { current ->
            current.copy(directAudioPromptSkipNoSpeech = enabled)
        }
        persist()
    }

    fun setConversationContextEnabled(enabled: Boolean) {
        _uiState.update { current ->
            current.copy(conversationContextEnabled = enabled)
        }
        persist()
    }

    fun setConversationContextTurns(turns: Int) {
        _uiState.update { current ->
            current.copy(conversationContextTurns = turns.coerceIn(1, 10))
        }
        persist()
    }

    fun setLanguageVisible(localeTag: String, visible: Boolean) {
        _uiState.update { current ->
            val next = current.visibleLanguageTags.toMutableSet()
            if (visible) {
                next += localeTag
            } else {
                if (localeTag.equals(current.sourceLanguage.localeTag, ignoreCase = true) ||
                    localeTag.equals(current.targetLanguage.localeTag, ignoreCase = true)
                ) {
                    return@update current.copy(
                        statusText = "Source/target language cannot be hidden."
                    )
                }
                if (next.size <= 1) {
                    return@update current.copy(
                        statusText = "At least one language must remain visible."
                    )
                }
                next -= localeTag
            }
            current.copy(
                visibleLanguageTags = next,
                statusText = "Visible language list updated."
            )
        }
        persist()
    }

    fun toggleListeningFor(role: InputRole) {
        val state = _uiState.value
        if (state.isListening && state.activeInputRole == role) {
            stopListeningAndFinalize("Microphone stopped.")
        } else {
            startListeningFor(role)
        }
    }

    fun stopListeningForRole(
        role: InputRole,
        reason: String = "Microphone stopped."
    ) {
        val state = _uiState.value
        if (!state.isListening || state.activeInputRole != role) {
            return
        }
        stopListeningAndFinalize(reason)
    }

    fun startListeningFor(role: InputRole) {
        val state = _uiState.value
        if (state.isListening) {
            if (state.activeInputRole == role) {
                return
            }
            _uiState.update { it.copy(statusText = "Microphone is already active.") }
            return
        }
        if (state.modelFileUri.isNullOrBlank()) {
            _uiState.update {
                it.copy(statusText = "Set model file first in Settings.")
            }
            return
        }

        val language = languageForRole(
            role = role,
            sourceLanguage = state.sourceLanguage,
            targetLanguage = state.targetLanguage
        )
        val sourceLanguage = sourceLanguageForRole(
            role = role,
            sourceLanguage = state.sourceLanguage,
            targetLanguage = state.targetLanguage
        )
        val targetLanguage = targetLanguageForRole(
            role = role,
            sourceLanguage = state.sourceLanguage,
            targetLanguage = state.targetLanguage
        )
        val directAudioMode = isDirectGemmaAudioMode(state.mode)
        cancelPendingStopFinalize()
        currentListeningSessionId += 1L
        clearStopFinalizedMarker()
        resetListeningTranscriptBuffer()
        suppressPostStopRecognizerFinalSubmission = false
        latestRecognitionRole = role
        _uiState.update {
            val liveCardId = messageIdCounter.incrementAndGet()
            it.copy(
                isListening = true,
                activeInputRole = role,
                lastInputRole = role,
                partialTranscript = "",
                liveCard = if (directAudioMode && !it.isTranslating) {
                    LiveTranslationCard(
                        id = liveCardId,
                        mode = state.mode,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        sourceText = "",
                        translatedText = "",
                        phase = LiveCardPhase.RECOGNIZING
                    )
                } else {
                    it.liveCard
                },
                statusText = if (directAudioMode) {
                    "Recording with Gemma 3n (${language.displayName})..."
                } else {
                    "Starting microphone (${language.displayName})..."
                }
            )
        }
    }

    fun stopListening(reason: String) {
        cancelPendingStopFinalize()
        resetListeningTranscriptBuffer()
        suppressPostStopRecognizerFinalSubmission = false
        _uiState.update {
            it.copy(
                isListening = false,
                activeInputRole = null,
                partialTranscript = "",
                liveCard = null,
                statusText = reason
            )
        }
    }

    fun onPartialTranscript(text: String) {
        _uiState.update { current ->
            if (current.isTranslating) {
                return@update current.copy(partialTranscript = text)
            }
            val partial = text.trim()
            if (partial.isBlank()) {
                listeningLatestPartial = ""
                return@update current
            }
            listeningLatestPartial = partial
            val aggregated = buildListeningAggregatedTranscript(includePartial = true)
            val role = current.activeInputRole ?: current.lastInputRole ?: latestRecognitionRole
            val sourceLanguage = sourceLanguageForRole(
                role = role,
                sourceLanguage = current.sourceLanguage,
                targetLanguage = current.targetLanguage
            )
            val targetLanguage = targetLanguageForRole(
                role = role,
                sourceLanguage = current.sourceLanguage,
                targetLanguage = current.targetLanguage
            )
            val liveCardId = current.liveCard?.id ?: messageIdCounter.incrementAndGet()
            current.copy(
                partialTranscript = aggregated,
                liveCard = current.liveCard.asRecognizing(
                    mode = current.mode,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    sourceText = aggregated,
                    fallbackId = liveCardId
                )
            )
        }
    }

    fun onFinalTranscript(text: String) {
        val snapshot = _uiState.value
        val role = snapshot.activeInputRole ?: snapshot.lastInputRole ?: latestRecognitionRole
        val finalized = text.trim()
        if (!snapshot.isListening && suppressPostStopRecognizerFinalSubmission) {
            return
        }
        if (finalized.isNotBlank()) {
            appendListeningFinalSegment(finalized)
            listeningLatestPartial = ""
            val aggregated = buildListeningAggregatedTranscript(includePartial = false)
            _uiState.update { current ->
                if (current.isTranslating) {
                    return@update current.copy(partialTranscript = aggregated)
                }
                val sourceLanguage = sourceLanguageForRole(
                    role = role,
                    sourceLanguage = current.sourceLanguage,
                    targetLanguage = current.targetLanguage
                )
                val targetLanguage = targetLanguageForRole(
                    role = role,
                    sourceLanguage = current.sourceLanguage,
                    targetLanguage = current.targetLanguage
                )
                val liveCardId = current.liveCard?.id ?: messageIdCounter.incrementAndGet()
                current.copy(
                    partialTranscript = aggregated,
                    liveCard = current.liveCard.asRecognized(
                        mode = current.mode,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        sourceText = aggregated,
                        fallbackId = liveCardId
                    )
                )
            }
        }
        if (snapshot.isListening) {
            // PTT mode: while the button is still pressed, only update recognized text.
            // Translation execution is triggered when the button is released.
            return
        }
        // After stop, we debounce final submission in stopListeningAndFinalize().
        return
    }

    fun onGemmaAudioCaptured(
        role: InputRole?,
        audioWavBytes: ByteArray?
    ) {
        if (audioWavBytes == null || audioWavBytes.isEmpty()) {
            _uiState.update { current ->
                if (current.isTranslating) {
                    return@update current.copy(
                        statusText = "No audio captured."
                    )
                }
                current.copy(
                    isTranslating = false,
                    streamingSourceText = "",
                    streamingTranslatedText = "",
                    isModelLoading = false,
                    modelLoadProgressPercent = null,
                    modelLoadStatusText = "",
                    liveCard = current.liveCard?.let { card ->
                        card.copy(
                            phase = LiveCardPhase.RECOGNIZED,
                            sourceText = card.sourceText
                        )
                    },
                    statusText = "No audio captured."
                )
            }
            return
        }
        val audioLevels = analyzeWavAudioLevels(audioWavBytes)
        if (audioLevels == null || isNearlySilentAudio(audioLevels)) {
            markSilentAudioSkipped()
            return
        }
        submitAudioInput(audioWavBytes = audioWavBytes, role = role)
    }

    private fun stopListeningAndFinalize(reason: String) {
        val snapshot = _uiState.value
        val role = snapshot.activeInputRole ?: latestRecognitionRole
        val partial = snapshot.partialTranscript.trim()
        val consolidated = if (partial.isNotBlank()) {
            partial
        } else {
            buildListeningAggregatedTranscript(includePartial = true)
        }

        _uiState.update {
            it.copy(
                isListening = false,
                activeInputRole = null,
                statusText = reason
            )
        }

        cancelPendingStopFinalize()
        pendingStopFinalizeJob = viewModelScope.launch {
            delay(STOP_FINALIZE_DEBOUNCE_MS)
            val finalText = buildListeningAggregatedTranscript(includePartial = true)
                .ifBlank { consolidated }
                .trim()
            if (finalText.isBlank()) {
                return@launch
            }
            markStopFinalizedTranscript(text = finalText, role = role)
            if (isTranscriptAlreadySubmittedInCurrentSession(text = finalText, role = role)) {
                return@launch
            }
            submitTranscript(text = finalText, role = role)
        }
    }

    private fun interruptListeningForRetranslate() {
        val snapshot = _uiState.value
        if (!snapshot.isListening) return
        cancelPendingStopFinalize()
        suppressPostStopRecognizerFinalSubmission = true
        resetListeningTranscriptBuffer()
        _uiState.update { current ->
            current.copy(
                isListening = false,
                activeInputRole = null,
                partialTranscript = "",
                statusText = "Retranslating edited text..."
            )
        }
    }

    private fun submitTranscript(
        text: String,
        role: InputRole?,
        overrideMode: TranslationMode? = null,
        overrideSourceLanguage: AppLanguage? = null,
        overrideTargetLanguage: AppLanguage? = null,
        overrideLiveCardId: Long? = null,
        skipDuplicateGuard: Boolean = false,
        replaceMessageId: Long? = null,
        allowQueue: Boolean = true
    ) {
        val transcript = text.trim()
        if (transcript.isBlank()) return
        if (!isMeaningfulTranscript(transcript)) {
            _uiState.update { current ->
                if (replaceMessageId != null) {
                    current.copy(
                        isTranslating = false,
                        streamingSourceText = "",
                        streamingTranslatedText = "",
                        isModelLoading = false,
                        modelLoadProgressPercent = null,
                        modelLoadStatusText = "",
                        statusText = "No meaningful speech recognized. Skipped translation."
                    )
                } else {
                    current.copy(
                        isTranslating = false,
                        streamingSourceText = "",
                        streamingTranslatedText = "",
                        isModelLoading = false,
                        modelLoadProgressPercent = null,
                        modelLoadStatusText = "",
                        liveCard = current.liveCard.asRecognized(
                            mode = overrideMode ?: current.mode,
                            sourceLanguage = overrideSourceLanguage ?: current.sourceLanguage,
                            targetLanguage = overrideTargetLanguage ?: current.targetLanguage,
                            sourceText = transcript,
                            fallbackId = overrideLiveCardId ?: current.liveCard?.id ?: messageIdCounter.incrementAndGet()
                        ),
                        statusText = "No meaningful speech recognized. Skipped translation."
                    )
                }
            }
            return
        }

        if (!skipDuplicateGuard) {
            val now = System.currentTimeMillis()
            if (transcript.equals(lastFinalTranscript, ignoreCase = true) &&
                now - lastFinalTimestamp < 1500L
            ) {
                return
            }
            lastFinalTranscript = transcript
            lastFinalTimestamp = now
            markTranscriptSubmittedInCurrentSession(
                text = transcript,
                role = role
            )
        }

        val stateSnapshot = _uiState.value
        val sourceLanguage = overrideSourceLanguage ?: sourceLanguageForRole(
            role = role,
            sourceLanguage = stateSnapshot.sourceLanguage,
            targetLanguage = stateSnapshot.targetLanguage
        )
        val targetLanguage = overrideTargetLanguage ?: targetLanguageForRole(
            role = role,
            sourceLanguage = stateSnapshot.sourceLanguage,
            targetLanguage = stateSnapshot.targetLanguage
        )
        val replacingMessageId = replaceMessageId
        val existingMessage = replacingMessageId?.let { id ->
            stateSnapshot.messages.firstOrNull { message -> message.id == id }
        }
        val updateMessageInPlace = replacingMessageId != null && existingMessage != null
        val previousTranslatedText = existingMessage?.translatedText.orEmpty()
        val liveCardId = when {
            updateMessageInPlace -> replacingMessageId
            overrideLiveCardId != null -> overrideLiveCardId
            else -> messageIdCounter.incrementAndGet()
        }
        val liveCardMode = overrideMode ?: stateSnapshot.liveCard?.mode ?: stateSnapshot.mode

        if (!updateMessageInPlace && allowQueue && translationJob != null) {
            val placeholderMessageId = messageIdCounter.incrementAndGet()
            val queuedMessage = ChatMessage(
                id = placeholderMessageId,
                mode = liveCardMode,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                sourceText = transcript,
                translatedText = QUEUED_PLACEHOLDER_TEXT
            )
            _uiState.update { current ->
                current.copy(
                    messages = (listOf(queuedMessage) + current.messages).take(MAX_HISTORY_COUNT)
                )
            }
            val queuedCount = enqueueTranslationWork {
                submitTranscript(
                    text = transcript,
                    role = role,
                    overrideMode = overrideMode,
                    overrideSourceLanguage = overrideSourceLanguage,
                    overrideTargetLanguage = overrideTargetLanguage,
                    overrideLiveCardId = placeholderMessageId,
                    skipDuplicateGuard = true,
                    replaceMessageId = placeholderMessageId,
                    allowQueue = false
                )
            }
            _uiState.update { current ->
                current.copy(
                    statusText = "Translating current request. Queued: $queuedCount"
                )
            }
            return
        }

        val translationToken = translationTokenCounter.incrementAndGet()
        if (updateMessageInPlace) {
            abortActiveTranslationForNewRequest()
        }
        activeTranslationToken = translationToken

        val job = viewModelScope.launch {
            try {
                resetStreamingUiUpdateState()
                _uiState.update { current ->
                    if (updateMessageInPlace) {
                        current.copy(
                            isTranslating = true,
                            streamingSourceText = transcript,
                            streamingTranslatedText = "",
                            isModelLoading = false,
                            modelLoadProgressPercent = null,
                            modelLoadStatusText = "",
                            liveCard = null,
                            messages = current.messages.map { message ->
                                if (message.id == replacingMessageId) {
                                    message.copy(
                                        sourceText = transcript,
                                        translatedText = ""
                                    )
                                } else {
                                    message
                                }
                            },
                            statusText = "Retranslating..."
                        )
                    } else {
                        current.copy(
                            isTranslating = true,
                            streamingSourceText = transcript,
                            streamingTranslatedText = "",
                            isModelLoading = false,
                            modelLoadProgressPercent = null,
                            modelLoadStatusText = "",
                            liveCard = current.liveCard.asTranslating(
                                mode = liveCardMode,
                                sourceLanguage = sourceLanguage,
                                targetLanguage = targetLanguage,
                                sourceText = transcript,
                                translatedText = "",
                                fallbackId = liveCardId
                            ),
                            statusText = "Translating..."
                        )
                    }
                }
                val request = TranslationRequest(
                    transcript = transcript,
                    audioWavBytes = null,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    modelFileUri = stateSnapshot.modelFileUri,
                    directAudioPromptSkipNoSpeech = stateSnapshot.directAudioPromptSkipNoSpeech,
                    directAudioSilenceSensitivity = stateSnapshot.directAudioSilenceSensitivity,
                    conversationContext = buildConversationContext(
                        includeMessageId = replacingMessageId
                    )
                )
                val engine = engineFactory.forMode(liveCardMode)
                val output = try {
                    engine.translate(
                        request = request,
                        onPartialResult = { partial ->
                            if (!isTranslationTokenActive(translationToken)) {
                                return@translate
                            }
                            if (shouldEmitStreamingUiUpdate(partial)) {
                                _uiState.update { current ->
                                    if (updateMessageInPlace) {
                                        current.copy(
                                            isTranslating = true,
                                            streamingSourceText = transcript,
                                            streamingTranslatedText = partial,
                                            messages = current.messages.map { message ->
                                                if (message.id == replacingMessageId) {
                                                    message.copy(
                                                        sourceText = transcript,
                                                        translatedText = partial
                                                    )
                                                } else {
                                                    message
                                                }
                                            }
                                        )
                                    } else {
                                        current.copy(
                                            isTranslating = true,
                                            streamingSourceText = transcript,
                                            streamingTranslatedText = partial,
                                            liveCard = current.liveCard.asTranslating(
                                                mode = liveCardMode,
                                                sourceLanguage = sourceLanguage,
                                                targetLanguage = targetLanguage,
                                                sourceText = transcript,
                                                translatedText = partial,
                                                fallbackId = liveCardId
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        onStatusUpdate = { status ->
                            if (!isTranslationTokenActive(translationToken)) {
                                return@translate
                            }
                            _uiState.update { current ->
                                current.copy(
                                    isModelLoading = status.isModelLoading,
                                    modelLoadProgressPercent =
                                        status.modelLoadProgressPercent ?: current.modelLoadProgressPercent,
                                    modelLoadStatusText = status.message,
                                    statusText = status.message
                                )
                            }
                        }
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    TranslationOutput(
                        sourceText = transcript,
                        translatedText = "",
                        statusMessage = "Translation failed: ${error.message ?: error.javaClass.simpleName}",
                        runtimeName = null
                    )
                }
                if (!isTranslationTokenActive(translationToken)) {
                    return@launch
                }
                if (output.translatedText.isBlank()) {
                    _uiState.update { current ->
                        if (updateMessageInPlace) {
                            current.copy(
                                isTranslating = false,
                                streamingSourceText = "",
                                streamingTranslatedText = "",
                                isModelLoading = false,
                                modelLoadProgressPercent = null,
                                messages = current.messages.map { message ->
                                    if (message.id == replacingMessageId) {
                                        message.copy(
                                            sourceText = transcript,
                                            translatedText = previousTranslatedText
                                        )
                                    } else {
                                        message
                                    }
                                },
                                statusText = output.statusMessage
                            )
                        } else {
                            current.copy(
                                isTranslating = false,
                                streamingSourceText = "",
                                streamingTranslatedText = "",
                                isModelLoading = false,
                                modelLoadProgressPercent = null,
                                liveCard = current.liveCard.asRecognized(
                                    mode = liveCardMode,
                                    sourceLanguage = sourceLanguage,
                                    targetLanguage = targetLanguage,
                                    sourceText = transcript,
                                    fallbackId = liveCardId
                                ),
                                statusText = output.statusMessage
                            )
                        }
                    }
                    return@launch
                }

                if (isNoSpeechMarker(output.translatedText)) {
                    handleNoSpeechOutput(
                        replacingMessageId = replacingMessageId,
                        previousSourceText = transcript,
                        previousTranslatedText = previousTranslatedText
                    )
                    return@launch
                }

                val runtimeName = output.runtimeName.orEmpty()
                val runtimeBackend = "GPU"

                val message = ChatMessage(
                    id = liveCardId,
                    mode = liveCardMode,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    sourceText = output.sourceText?.takeIf { it.isNotBlank() } ?: transcript,
                    translatedText = output.translatedText
                )

                _uiState.update { current ->
                    if (updateMessageInPlace) {
                        return@update current.copy(
                            isTranslating = false,
                            streamingSourceText = "",
                            streamingTranslatedText = "",
                            isModelLoading = false,
                            modelLoadProgressPercent = null,
                            liveCard = null,
                            currentRuntimeBackend = runtimeBackend,
                            currentRuntimeName = runtimeName,
                            statusText = output.statusMessage,
                            messages = current.messages.map { existing ->
                                if (existing.id == replacingMessageId) {
                                    message.copy(timestampMillis = existing.timestampMillis)
                                } else {
                                    existing
                                }
                            }
                        )
                    }
                    val latest = current.messages.firstOrNull { messageItem ->
                        !isQueuedPlaceholderMessage(messageItem)
                    } ?: current.messages.firstOrNull()
                    val isDuplicate = latest != null && isDuplicateMessage(
                        latest = latest,
                        candidate = message
                    )
                    if (isDuplicate) {
                        return@update current.copy(
                            isTranslating = false,
                            streamingSourceText = "",
                            streamingTranslatedText = "",
                            isModelLoading = false,
                            modelLoadProgressPercent = null,
                            liveCard = null,
                            currentRuntimeBackend = runtimeBackend,
                            currentRuntimeName = runtimeName,
                            statusText = output.statusMessage
                        )
                    }
                    val hasQueuedPlaceholders = current.messages.any { messageItem ->
                        isQueuedPlaceholderMessage(messageItem)
                    }
                    val mergedMessages = if (hasQueuedPlaceholders) {
                        (current.messages + message).take(MAX_HISTORY_COUNT)
                    } else {
                        (listOf(message) + current.messages).take(MAX_HISTORY_COUNT)
                    }
                    current.copy(
                        isTranslating = false,
                        streamingSourceText = "",
                        streamingTranslatedText = "",
                        isModelLoading = false,
                        modelLoadProgressPercent = null,
                        liveCard = null,
                        currentRuntimeBackend = runtimeBackend,
                        currentRuntimeName = runtimeName,
                        statusText = output.statusMessage,
                        messages = mergedMessages
                    )
                }
            } catch (_: CancellationException) {
            } finally {
                if (translationJob === coroutineContext[Job]) {
                    translationJob = null
                    activeTranslationMode = null
                    activeTranslationMessageId = null
                    activeTranslationToken = null
                    launchNextQueuedTranslationIfIdle()
                }
            }
        }
        translationJob = job
        activeTranslationMode = liveCardMode
        activeTranslationMessageId = if (updateMessageInPlace) replacingMessageId else null
        activeTranslationToken = translationToken
    }

    private fun submitAudioInput(
        audioWavBytes: ByteArray,
        role: InputRole?,
        overrideMode: TranslationMode? = null,
        overrideSourceLanguage: AppLanguage? = null,
        overrideTargetLanguage: AppLanguage? = null,
        overrideLiveCardId: Long? = null,
        replaceMessageId: Long? = null,
        allowQueue: Boolean = true
    ) {
        val stateSnapshot = _uiState.value
        val sourceLanguage = overrideSourceLanguage ?: sourceLanguageForRole(
            role = role,
            sourceLanguage = stateSnapshot.sourceLanguage,
            targetLanguage = stateSnapshot.targetLanguage
        )
        val targetLanguage = overrideTargetLanguage ?: targetLanguageForRole(
            role = role,
            sourceLanguage = stateSnapshot.sourceLanguage,
            targetLanguage = stateSnapshot.targetLanguage
        )
        val liveCardId = overrideLiveCardId ?: messageIdCounter.incrementAndGet()
        val replacingMessageId = replaceMessageId
        val existingMessage = replacingMessageId?.let { id ->
            stateSnapshot.messages.firstOrNull { message -> message.id == id }
        }
        val updateMessageInPlace = replacingMessageId != null && existingMessage != null
        val previousTranslatedText = existingMessage?.translatedText.orEmpty()
        val previousSourceText = existingMessage?.sourceText
        val liveCardMode = overrideMode ?: stateSnapshot.liveCard?.mode ?: stateSnapshot.mode
        val sourceText = ""
        val isDirectAudioMode = liveCardMode == TranslationMode.GEMMA_3N_E4B_LITERTLM_DIRECT_AUDIO

        if (allowQueue && translationJob != null) {
            val placeholderMessageId = messageIdCounter.incrementAndGet()
            val queuedMessage = ChatMessage(
                id = placeholderMessageId,
                mode = liveCardMode,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                sourceText = null,
                translatedText = QUEUED_PLACEHOLDER_TEXT
            )
            _uiState.update { current ->
                current.copy(
                    messages = (listOf(queuedMessage) + current.messages).take(MAX_HISTORY_COUNT)
                )
            }
            val queuedAudio = audioWavBytes.copyOf()
            val queuedCount = enqueueTranslationWork {
                submitAudioInput(
                    audioWavBytes = queuedAudio,
                    role = role,
                    overrideMode = liveCardMode,
                    overrideSourceLanguage = sourceLanguage,
                    overrideTargetLanguage = targetLanguage,
                    overrideLiveCardId = placeholderMessageId,
                    replaceMessageId = placeholderMessageId,
                    allowQueue = false
                )
            }
            _uiState.update { current ->
                current.copy(
                    statusText = "Translating current request. Queued: $queuedCount"
                )
            }
            return
        }

        val translationToken = translationTokenCounter.incrementAndGet()
        activeTranslationToken = translationToken

        val job = viewModelScope.launch {
            try {
                resetStreamingUiUpdateState()
                _uiState.update { current ->
                    if (updateMessageInPlace) {
                        current.copy(
                            isTranslating = true,
                            streamingSourceText = previousSourceText.orEmpty(),
                            streamingTranslatedText = "",
                            isModelLoading = false,
                            modelLoadProgressPercent = null,
                            modelLoadStatusText = "",
                            liveCard = null,
                            messages = current.messages.map { message ->
                                if (message.id == replacingMessageId) {
                                    message.copy(
                                        sourceText = previousSourceText,
                                        translatedText = ""
                                    )
                                } else {
                                    message
                                }
                            },
                            statusText = "Translating audio..."
                        )
                    } else {
                        current.copy(
                            isTranslating = true,
                            streamingSourceText = sourceText,
                            streamingTranslatedText = "",
                            isModelLoading = false,
                            modelLoadProgressPercent = null,
                            modelLoadStatusText = "",
                            liveCard = current.liveCard.asTranslating(
                                mode = liveCardMode,
                                sourceLanguage = sourceLanguage,
                                targetLanguage = targetLanguage,
                                sourceText = sourceText,
                                translatedText = "",
                                fallbackId = liveCardId
                            ),
                            statusText = "Translating audio..."
                        )
                    }
                }
                val request = TranslationRequest(
                    transcript = sourceText,
                    audioWavBytes = audioWavBytes,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    modelFileUri = stateSnapshot.modelFileUri,
                    directAudioPromptSkipNoSpeech = stateSnapshot.directAudioPromptSkipNoSpeech,
                    directAudioSilenceSensitivity = stateSnapshot.directAudioSilenceSensitivity,
                    conversationContext = buildConversationContext(
                        includeMessageId = replacingMessageId
                    )
                )
                val engine = engineFactory.forMode(liveCardMode)
                val output = try {
                    engine.translate(
                        request = request,
                        onPartialResult = { partial ->
                            if (!isTranslationTokenActive(translationToken)) {
                                return@translate
                            }
                            if (shouldEmitStreamingUiUpdate(partial)) {
                                _uiState.update { current ->
                                    val parsed = if (isDirectAudioMode) {
                                        parseDirectAudioStreamingPartial(partial)
                                    } else {
                                        null
                                    }
                                    val nextSourceText = when {
                                        parsed?.recognizedText?.isNotBlank() == true -> parsed.recognizedText
                                        current.liveCard?.sourceText?.isNotBlank() == true -> {
                                            current.liveCard.sourceText
                                        }
                                        else -> sourceText
                                    }
                                    val nextTranslatedText = when {
                                        parsed?.translatedText?.isNotBlank() == true -> parsed.translatedText
                                        !isDirectAudioMode -> partial
                                        current.liveCard?.translatedText?.isNotBlank() == true -> {
                                            current.liveCard.translatedText
                                        }
                                        else -> ""
                                    }
                                    if (updateMessageInPlace) {
                                        current.copy(
                                            isTranslating = true,
                                            streamingSourceText = nextSourceText,
                                            streamingTranslatedText = nextTranslatedText,
                                            messages = current.messages.map { message ->
                                                if (message.id == replacingMessageId) {
                                                    message.copy(
                                                        sourceText = nextSourceText.takeIf { it.isNotBlank() },
                                                        translatedText = nextTranslatedText
                                                    )
                                                } else {
                                                    message
                                                }
                                            }
                                        )
                                    } else {
                                        current.copy(
                                            isTranslating = true,
                                            streamingSourceText = nextSourceText,
                                            streamingTranslatedText = nextTranslatedText,
                                            liveCard = current.liveCard.asTranslating(
                                                mode = liveCardMode,
                                                sourceLanguage = sourceLanguage,
                                                targetLanguage = targetLanguage,
                                                sourceText = nextSourceText,
                                                translatedText = nextTranslatedText,
                                                fallbackId = liveCardId
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        onStatusUpdate = { status ->
                            if (!isTranslationTokenActive(translationToken)) {
                                return@translate
                            }
                            _uiState.update { current ->
                                current.copy(
                                    isModelLoading = status.isModelLoading,
                                    modelLoadProgressPercent =
                                        status.modelLoadProgressPercent ?: current.modelLoadProgressPercent,
                                    modelLoadStatusText = status.message,
                                    statusText = status.message
                                )
                            }
                        }
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    TranslationOutput(
                        sourceText = sourceText,
                        translatedText = "",
                        statusMessage = "Translation failed: ${error.message ?: error.javaClass.simpleName}",
                        runtimeName = null
                    )
                }
                if (!isTranslationTokenActive(translationToken)) {
                    return@launch
                }
                if (output.translatedText.isBlank()) {
                    _uiState.update { current ->
                        if (updateMessageInPlace) {
                            current.copy(
                                isTranslating = false,
                                streamingSourceText = "",
                                streamingTranslatedText = "",
                                isModelLoading = false,
                                modelLoadProgressPercent = null,
                                messages = current.messages.map { message ->
                                    if (message.id == replacingMessageId) {
                                        message.copy(
                                            sourceText = previousSourceText,
                                            translatedText = previousTranslatedText
                                        )
                                    } else {
                                        message
                                    }
                                },
                                statusText = output.statusMessage
                            )
                        } else {
                            current.copy(
                                isTranslating = false,
                                streamingSourceText = "",
                                streamingTranslatedText = "",
                                isModelLoading = false,
                                modelLoadProgressPercent = null,
                                liveCard = current.liveCard.asRecognized(
                                    mode = liveCardMode,
                                    sourceLanguage = sourceLanguage,
                                    targetLanguage = targetLanguage,
                                    sourceText = sourceText,
                                    fallbackId = liveCardId
                                ),
                                statusText = output.statusMessage
                            )
                        }
                    }
                    return@launch
                }

                if (isNoSpeechMarker(output.translatedText)) {
                    handleNoSpeechOutput(
                        replacingMessageId = replacingMessageId,
                        previousSourceText = previousSourceText,
                        previousTranslatedText = previousTranslatedText
                    )
                    return@launch
                }

                val runtimeName = output.runtimeName.orEmpty()
                val runtimeBackend = "GPU"

                val message = ChatMessage(
                    id = liveCardId,
                    mode = liveCardMode,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    sourceText = output.sourceText?.takeIf { it.isNotBlank() },
                    translatedText = output.translatedText
                )

                _uiState.update { current ->
                    if (updateMessageInPlace) {
                        return@update current.copy(
                            isTranslating = false,
                            streamingSourceText = "",
                            streamingTranslatedText = "",
                            isModelLoading = false,
                            modelLoadProgressPercent = null,
                            liveCard = null,
                            currentRuntimeBackend = runtimeBackend,
                            currentRuntimeName = runtimeName,
                            statusText = output.statusMessage,
                            messages = current.messages.map { existing ->
                                if (existing.id == replacingMessageId) {
                                    message.copy(timestampMillis = existing.timestampMillis)
                                } else {
                                    existing
                                }
                            }
                        )
                    }
                    val latest = current.messages.firstOrNull { messageItem ->
                        !isQueuedPlaceholderMessage(messageItem)
                    } ?: current.messages.firstOrNull()
                    val isDuplicate = latest != null && isDuplicateMessage(
                        latest = latest,
                        candidate = message
                    )
                    if (isDuplicate) {
                        return@update current.copy(
                            isTranslating = false,
                            streamingSourceText = "",
                            streamingTranslatedText = "",
                            isModelLoading = false,
                            modelLoadProgressPercent = null,
                            liveCard = null,
                            currentRuntimeBackend = runtimeBackend,
                            currentRuntimeName = runtimeName,
                            statusText = output.statusMessage
                        )
                    }
                    val hasQueuedPlaceholders = current.messages.any { messageItem ->
                        isQueuedPlaceholderMessage(messageItem)
                    }
                    val mergedMessages = if (hasQueuedPlaceholders) {
                        (current.messages + message).take(MAX_HISTORY_COUNT)
                    } else {
                        (listOf(message) + current.messages).take(MAX_HISTORY_COUNT)
                    }
                    current.copy(
                        isTranslating = false,
                        streamingSourceText = "",
                        streamingTranslatedText = "",
                        isModelLoading = false,
                        modelLoadProgressPercent = null,
                        liveCard = null,
                        currentRuntimeBackend = runtimeBackend,
                        currentRuntimeName = runtimeName,
                        statusText = output.statusMessage,
                        messages = mergedMessages
                    )
                }
            } catch (_: CancellationException) {
            } finally {
                if (translationJob === coroutineContext[Job]) {
                    translationJob = null
                    activeTranslationMode = null
                    activeTranslationMessageId = null
                    activeTranslationToken = null
                    launchNextQueuedTranslationIfIdle()
                }
            }
        }
        translationJob = job
        activeTranslationMode = liveCardMode
        activeTranslationMessageId = if (updateMessageInPlace) replacingMessageId else null
        activeTranslationToken = translationToken
    }

    fun setStatus(status: String) {
        _uiState.update { it.copy(statusText = status) }
    }

    fun clearHistory() {
        cancelPendingStopFinalize()
        cancelActiveTranslationByUser(
            reason = "Translation canceled and history cleared.",
            clearLiveCard = true
        )
        _uiState.update {
            it.copy(
                messages = emptyList(),
                partialTranscript = "",
                isTranslating = false,
                streamingSourceText = "",
                streamingTranslatedText = "",
                isModelLoading = false,
                modelLoadProgressPercent = null,
                modelLoadStatusText = "",
                liveCard = null,
                statusText = "History cleared."
            )
        }
    }

    fun removeMessage(messageId: Long) {
        if (_uiState.value.isTranslating && activeTranslationMessageId == messageId) {
            cancelActiveTranslationByUser(
                reason = "Translation canceled.",
                clearLiveCard = false
            )
        }
        _uiState.update { current ->
            val updated = current.messages.filterNot { it.id == messageId }
            if (updated.size == current.messages.size) {
                current
            } else {
                current.copy(messages = updated, statusText = "Translation removed.")
            }
        }
    }

    fun removeLiveCard() {
        val snapshot = _uiState.value
        if (snapshot.isTranslating) {
            cancelActiveTranslationByUser(
                reason = "Translation canceled.",
                clearLiveCard = true
            )
            return
        }
        _uiState.update { current ->
            current.copy(
                liveCard = null,
                partialTranscript = "",
                statusText = "Live entry removed."
            )
        }
    }

    fun retranslateLiveCard(editedText: String) {
        val snapshot = _uiState.value
        val card = snapshot.liveCard ?: return
        interruptListeningForRetranslate()
        abortActiveTranslationForNewRequest()
        submitTranscript(
            text = editedText,
            role = null,
            overrideMode = card.mode,
            overrideSourceLanguage = card.sourceLanguage,
            overrideTargetLanguage = card.targetLanguage,
            overrideLiveCardId = card.id,
            skipDuplicateGuard = true,
            allowQueue = false
        )
    }

    fun retranslateMessage(
        messageId: Long,
        editedText: String
    ) {
        val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        interruptListeningForRetranslate()
        submitTranscript(
            text = editedText,
            role = null,
            overrideMode = message.mode,
            overrideSourceLanguage = message.sourceLanguage,
            overrideTargetLanguage = message.targetLanguage,
            overrideLiveCardId = message.id,
            skipDuplicateGuard = true,
            replaceMessageId = messageId,
            allowQueue = false
        )
    }

    private fun resetListeningTranscriptBuffer() {
        listeningFinalSegments.clear()
        listeningLatestPartial = ""
    }

    private fun appendListeningFinalSegment(text: String) {
        val segment = text.trim()
        if (segment.isBlank()) return
        val normalized = normalizeRecognizedSegment(segment)
        val lastNormalized = listeningFinalSegments.lastOrNull()
            ?.let { normalizeRecognizedSegment(it) }
        if (normalized == lastNormalized) return
        listeningFinalSegments += segment
    }

    private fun buildListeningAggregatedTranscript(includePartial: Boolean): String {
        val pieces = mutableListOf<String>()
        listeningFinalSegments.forEach { segment ->
            val trimmed = segment.trim()
            if (trimmed.isBlank()) return@forEach
            val normalized = normalizeRecognizedSegment(trimmed)
            val lastNormalized = pieces.lastOrNull()
                ?.let { normalizeRecognizedSegment(it) }
            if (normalized != lastNormalized) {
                pieces += trimmed
            }
        }
        if (includePartial) {
            val partial = listeningLatestPartial.trim()
            if (partial.isNotBlank()) {
                val normalizedPartial = normalizeRecognizedSegment(partial)
                val lastNormalized = pieces.lastOrNull()
                    ?.let { normalizeRecognizedSegment(it) }
                if (normalizedPartial != lastNormalized) {
                    pieces += partial
                }
            }
        }
        return pieces.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeRecognizedSegment(text: String): String {
        return text.trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    private fun shouldSkipStopFinalDuplicate(
        transcript: String,
        role: InputRole?
    ): Boolean {
        if (transcript.isBlank()) return false
        val now = System.currentTimeMillis()
        val markedFingerprint = stopFinalizedFingerprint ?: return false
        val candidateFingerprint = transcriptFingerprint(transcript, role)
        val isDuplicate = markedFingerprint == candidateFingerprint &&
            now - stopFinalizedAtMillis <= STOP_FINAL_DUPLICATE_WINDOW_MS
        if (isDuplicate) {
            clearStopFinalizedMarker()
        }
        return isDuplicate
    }

    private fun markStopFinalizedTranscript(
        text: String,
        role: InputRole?
    ) {
        stopFinalizedFingerprint = transcriptFingerprint(text, role)
        stopFinalizedAtMillis = System.currentTimeMillis()
    }

    private fun markTranscriptSubmittedInCurrentSession(
        text: String,
        role: InputRole?
    ) {
        lastSubmittedFingerprint = transcriptFingerprint(text, role)
        lastSubmittedSessionId = currentListeningSessionId
    }

    private fun isTranscriptAlreadySubmittedInCurrentSession(
        text: String,
        role: InputRole?
    ): Boolean {
        val fingerprint = transcriptFingerprint(text, role)
        return lastSubmittedFingerprint == fingerprint &&
            lastSubmittedSessionId == currentListeningSessionId
    }

    private fun clearStopFinalizedMarker() {
        stopFinalizedFingerprint = null
        stopFinalizedAtMillis = 0L
    }

    private fun transcriptFingerprint(
        text: String,
        role: InputRole?
    ): String {
        return "${role ?: InputRole.SOURCE}|${text.trim().lowercase()}"
    }

    @Synchronized
    private fun resetStreamingUiUpdateState() {
        lastStreamingUiUpdateAtMillis = 0L
        lastStreamingUiText = ""
    }

    @Synchronized
    private fun shouldEmitStreamingUiUpdate(partial: String): Boolean {
        if (partial.isBlank() || partial == lastStreamingUiText) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastStreamingUiUpdateAtMillis
        val grewBy = partial.length - lastStreamingUiText.length
        val endsWithBoundary = partial.lastOrNull()?.let {
            STREAMING_FORCE_UPDATE_TERMINATORS.indexOf(it) >= 0
        } ?: false
        val shouldEmit = elapsed >= STREAMING_UI_UPDATE_INTERVAL_MS ||
            grewBy >= STREAMING_FORCE_UPDATE_CHAR_DELTA ||
            endsWithBoundary
        if (!shouldEmit) {
            return false
        }
        lastStreamingUiText = partial
        lastStreamingUiUpdateAtMillis = now
        return true
    }

    private fun isTranslationTokenActive(token: Long): Boolean {
        return activeTranslationToken == token
    }

    private fun cancelPendingStopFinalize() {
        pendingStopFinalizeJob?.cancel()
        pendingStopFinalizeJob = null
    }

    @Synchronized
    private fun enqueueTranslationWork(
        execute: () -> Unit
    ): Int {
        pendingTranslationQueue.addLast(QueuedTranslationWork(execute))
        return pendingTranslationQueue.size
    }

    @Synchronized
    private fun dequeueTranslationWork(): QueuedTranslationWork? {
        if (pendingTranslationQueue.isEmpty()) {
            return null
        }
        return pendingTranslationQueue.removeFirst()
    }

    @Synchronized
    private fun clearPendingTranslationQueue() {
        pendingTranslationQueue.clear()
    }

    private fun launchNextQueuedTranslationIfIdle() {
        if (translationJob != null) {
            return
        }
        val next = dequeueTranslationWork() ?: return
        next.execute.invoke()
    }

    private fun LiveTranslationCard?.asRecognizing(
        mode: TranslationMode,
        sourceLanguage: AppLanguage,
        targetLanguage: AppLanguage,
        sourceText: String,
        fallbackId: Long
    ): LiveTranslationCard {
        return LiveTranslationCard(
            id = this?.id ?: fallbackId,
            mode = this?.mode ?: mode,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            sourceText = sourceText,
            translatedText = "",
            phase = LiveCardPhase.RECOGNIZING
        )
    }

    private fun LiveTranslationCard?.asRecognized(
        mode: TranslationMode,
        sourceLanguage: AppLanguage,
        targetLanguage: AppLanguage,
        sourceText: String,
        fallbackId: Long
    ): LiveTranslationCard {
        return LiveTranslationCard(
            id = this?.id ?: fallbackId,
            mode = this?.mode ?: mode,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            sourceText = sourceText,
            translatedText = this?.translatedText.orEmpty(),
            phase = LiveCardPhase.RECOGNIZED
        )
    }

    private fun LiveTranslationCard?.asTranslating(
        mode: TranslationMode,
        sourceLanguage: AppLanguage,
        targetLanguage: AppLanguage,
        sourceText: String,
        translatedText: String,
        fallbackId: Long
    ): LiveTranslationCard {
        return LiveTranslationCard(
            id = this?.id ?: fallbackId,
            mode = this?.mode ?: mode,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            sourceText = sourceText,
            translatedText = translatedText,
            phase = LiveCardPhase.TRANSLATING
        )
    }

    private fun isDuplicateMessage(
        latest: ChatMessage,
        candidate: ChatMessage
    ): Boolean {
        if (isQueuedPlaceholderMessage(latest)) {
            return false
        }
        val sourceSame = latest.sourceText.orEmpty().trim() == candidate.sourceText.orEmpty().trim()
        val translatedSame = latest.translatedText.trim() == candidate.translatedText.trim()
        val pairSame = latest.sourceLanguage == candidate.sourceLanguage &&
            latest.targetLanguage == candidate.targetLanguage &&
            latest.mode == candidate.mode
        val closeInTime = candidate.timestampMillis - latest.timestampMillis <= DUPLICATE_MESSAGE_WINDOW_MS
        return sourceSame && translatedSame && pairSame && closeInTime
    }

    private fun isQueuedPlaceholderMessage(message: ChatMessage): Boolean {
        return message.translatedText == QUEUED_PLACEHOLDER_TEXT
    }

    private fun parseDirectAudioStreamingPartial(raw: String): DirectAudioStreamingPartial {
        val text = raw.trim()
        if (text.isBlank()) {
            return DirectAudioStreamingPartial(
                recognizedText = "",
                translatedText = ""
            )
        }

        val sourceFromLabel = extractStreamingLabeledValue(
            text = text,
            label = "SOURCE",
            otherLabel = "TRANSLATION"
        )
        val translationFromLabel = extractStreamingLabeledValue(
            text = text,
            label = "TRANSLATION",
            otherLabel = "SOURCE"
        )
        if (sourceFromLabel.isNotBlank() || translationFromLabel.isNotBlank()) {
            return DirectAudioStreamingPartial(
                recognizedText = sanitizeStreamingField(sourceFromLabel),
                translatedText = sanitizeStreamingField(translationFromLabel)
            )
        }

        val sourceFromTag = extractStreamingTaggedValue(
            text = text,
            tag = "source",
            otherTag = "translation"
        )
        val translationFromTag = extractStreamingTaggedValue(
            text = text,
            tag = "translation",
            otherTag = "source"
        )
        return DirectAudioStreamingPartial(
            recognizedText = sanitizeStreamingField(sourceFromTag),
            translatedText = sanitizeStreamingField(translationFromTag)
        )
    }

    private fun extractStreamingLabeledValue(
        text: String,
        label: String,
        otherLabel: String
    ): String {
        val labelToken = "$label:"
        val lower = text.lowercase()
        val start = lower.indexOf(labelToken.lowercase())
        if (start < 0) return ""

        val tail = text.substring(start + labelToken.length)
        val stop = firstStreamingMarkerIndex(
            text = tail,
            markers = listOf(
                "$otherLabel:",
                "<$otherLabel>",
                "$otherLabel>",
                "< /$otherLabel>"
            )
        )
        val value = if (stop >= 0) {
            tail.substring(0, stop)
        } else {
            tail
        }
        return value.trim()
    }

    private fun extractStreamingTaggedValue(
        text: String,
        tag: String,
        otherTag: String
    ): String {
        val lower = text.lowercase()
        val openCandidates = listOf(
            "<$tag>",
            "<$tag >",
            "$tag>"
        )
        var open = -1
        var openLength = 0
        for (candidate in openCandidates) {
            val idx = lower.indexOf(candidate.lowercase())
            if (idx >= 0 && (open < 0 || idx < open)) {
                open = idx
                openLength = candidate.length
            }
        }
        if (open < 0) return ""
        val tail = text.substring(open + openLength)
        val close = firstStreamingMarkerIndex(
            text = tail,
            markers = listOf(
                "</$tag>",
                "< /$tag>",
                "</ $tag>",
                "/$tag>",
                "<$otherTag>",
                "$otherTag>",
                "$otherTag:",
                "< /$otherTag>"
            )
        )
        return if (close >= 0) {
            tail.substring(0, close).trim()
        } else {
            tail.trim()
        }
    }

    private fun sanitizeStreamingField(value: String): String {
        if (value.isBlank()) return ""
        return value
            .replace(Regex("(?i)<\\s*/?\\s*(source|translation)\\b[^>]*>"), " ")
            .replace(Regex("(?i)</?\\s*(source|translation)\\b"), " ")
            .replace(Regex("(?i)\\b(source|translation)\\s*:\\s*"), " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun firstStreamingMarkerIndex(
        text: String,
        markers: List<String>
    ): Int {
        if (text.isEmpty() || markers.isEmpty()) return -1
        val lower = text.lowercase()
        var best = -1
        for (marker in markers) {
            val idx = lower.indexOf(marker.lowercase())
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx
            }
        }
        return best
    }

    private fun markSilentAudioSkipped() {
        _uiState.update { current ->
            if (current.isTranslating) {
                return@update current.copy(
                    liveCard = null,
                    statusText = "Input audio is nearly silent. Skipped translation."
                )
            }
            current.copy(
                isTranslating = false,
                streamingSourceText = "",
                streamingTranslatedText = "",
                isModelLoading = false,
                modelLoadProgressPercent = null,
                modelLoadStatusText = "",
                liveCard = null,
                statusText = "Input audio is nearly silent. Skipped translation."
            )
        }
    }

    private fun abortActiveTranslationForNewRequest(
        clearLiveCard: Boolean = false
    ) {
        cancelPendingStopFinalize()
        val runningJob = translationJob
        val mode = activeTranslationMode
        if (runningJob == null || mode == null) {
            return
        }
        runningJob.cancel(CancellationException("Replaced by a new translation request."))
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    engineFactory.forMode(mode).cancel()
                }
            }
        }
        translationJob = null
        activeTranslationMode = null
        activeTranslationMessageId = null
        activeTranslationToken = null
        clearPendingTranslationQueue()
        _uiState.update { current ->
            current.copy(
                isTranslating = false,
                streamingSourceText = "",
                streamingTranslatedText = "",
                isModelLoading = false,
                modelLoadProgressPercent = null,
                modelLoadStatusText = "",
                liveCard = if (clearLiveCard) null else current.liveCard
            )
        }
    }

    private fun cancelActiveTranslationByUser(
        reason: String,
        clearLiveCard: Boolean
    ) {
        cancelPendingStopFinalize()
        val runningJob = translationJob
        val mode = activeTranslationMode
        if (runningJob != null && mode != null) {
            runningJob.cancel(CancellationException(reason))
            viewModelScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        engineFactory.forMode(mode).cancel()
                    }
                }
            }
        }
        clearPendingTranslationQueue()
        translationJob = null
        activeTranslationMode = null
        activeTranslationMessageId = null
        activeTranslationToken = null
        _uiState.update { current ->
            current.copy(
                isTranslating = false,
                streamingSourceText = "",
                streamingTranslatedText = "",
                isModelLoading = false,
                modelLoadProgressPercent = null,
                modelLoadStatusText = "",
                liveCard = if (clearLiveCard) null else current.liveCard,
                statusText = reason
            )
        }
    }

    private fun isMeaningfulTranscript(text: String): Boolean {
        val normalized = text
            .replace(Regex("[\\s\\p{Punct}\\p{S}]"), "")
            .trim()
        return normalized.isNotBlank()
    }

    private fun analyzeWavAudioLevels(wavBytes: ByteArray): AudioLevelStats? {
        val dataOffset = locateWavDataOffset(wavBytes) ?: return null
        val available = wavBytes.size - dataOffset
        if (available < 2) return null

        var sampleCount = 0
        var activeSamples = 0
        var peakNorm = 0.0
        var sumSquares = 0.0

        var index = dataOffset
        while (index + 1 < wavBytes.size) {
            val low = wavBytes[index].toInt() and 0xFF
            val high = wavBytes[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            val normalized = sample / PCM_16_FULL_SCALE
            val absNorm = abs(normalized)
            if (absNorm > peakNorm) peakNorm = absNorm
            if (absNorm >= AUDIO_ACTIVE_SAMPLE_THRESHOLD) activeSamples += 1
            sumSquares += normalized * normalized
            sampleCount += 1
            index += 2
        }

        if (sampleCount <= 0) return null
        val rms = sqrt(sumSquares / sampleCount.toDouble())
        val rmsDbfs = 20.0 * log10(max(rms, AUDIO_DB_EPSILON))
        val peakDbfs = 20.0 * log10(max(peakNorm, AUDIO_DB_EPSILON))
        val activeRatio = activeSamples.toDouble() / sampleCount.toDouble()
        return AudioLevelStats(
            sampleCount = sampleCount,
            rmsDbfs = rmsDbfs,
            peakDbfs = peakDbfs,
            activeRatio = activeRatio
        )
    }

    private fun locateWavDataOffset(wavBytes: ByteArray): Int? {
        if (wavBytes.size < 12) return null
        val riff = readFourCc(wavBytes, 0)
        val wave = readFourCc(wavBytes, 8)
        if (riff != "RIFF" || wave != "WAVE") {
            val fallback = WAV_HEADER_BYTES.coerceAtMost(wavBytes.size)
            return if (wavBytes.size - fallback >= 2) fallback else null
        }

        var offset = 12
        while (offset + 8 <= wavBytes.size) {
            val chunkId = readFourCc(wavBytes, offset)
            val chunkSize = readIntLe(wavBytes, offset + 4)
            if (chunkSize < 0) {
                return null
            }
            val dataStart = offset + 8
            if (chunkId == "data") {
                if (dataStart >= wavBytes.size) return null
                return dataStart
            }
            val alignedChunkSize = chunkSize + (chunkSize and 1)
            val next = dataStart + alignedChunkSize
            if (next <= offset) return null
            offset = next
        }
        return null
    }

    private fun readFourCc(bytes: ByteArray, offset: Int): String {
        if (offset + 4 > bytes.size) return ""
        return String(bytes, offset, 4, Charsets.US_ASCII)
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    /**
     * Determines whether the captured audio is nearly silent.
     *
     * Uses three simple criteria — all of which must be satisfied to classify as silent:
     *   1. RMS energy (dBFS) below threshold
     *   2. Peak amplitude (dBFS) below threshold
     *   3. Active sample ratio below threshold
     *
     * The sensitivity slider (0–100) linearly interpolates each threshold between
     * a lenient extreme (sensitivity=0, harder to classify as silent) and a
     * strict extreme (sensitivity=100, easier to classify as silent).
     *
     *   sensitivity  0 → RMS ≤ -48 dB,  Peak ≤ -32 dB, ActiveRatio ≤ 0.02
     *   sensitivity 50 → RMS ≤ -36 dB,  Peak ≤ -22 dB, ActiveRatio ≤ 0.08  (default)
     *   sensitivity 100→ RMS ≤ -24 dB,  Peak ≤ -12 dB, ActiveRatio ≤ 0.14
     */
    private fun isNearlySilentAudio(levels: AudioLevelStats): Boolean {
        val sensitivity = _uiState.value.directAudioSilenceSensitivity.coerceIn(0, 100)
        val t = sensitivity / 100.0 // 0.0 .. 1.0

        // Linear interpolation: lerp(lenient, strict, t)
        val rmsThreshold  = SILENCE_RMS_DBFS_LENIENT  + t * (SILENCE_RMS_DBFS_STRICT  - SILENCE_RMS_DBFS_LENIENT)
        val peakThreshold = SILENCE_PEAK_DBFS_LENIENT + t * (SILENCE_PEAK_DBFS_STRICT - SILENCE_PEAK_DBFS_LENIENT)
        val activeRatioThreshold = SILENCE_ACTIVE_RATIO_LENIENT + t * (SILENCE_ACTIVE_RATIO_STRICT - SILENCE_ACTIVE_RATIO_LENIENT)

        Log.d(
            LOG_TAG,
            "silence-check  sensitivity=$sensitivity  " +
                "rms=${"%.1f".format(levels.rmsDbfs)}(≤${"%.1f".format(rmsThreshold)})  " +
                "peak=${"%.1f".format(levels.peakDbfs)}(≤${"%.1f".format(peakThreshold)})  " +
                "activeRatio=${"%.3f".format(levels.activeRatio)}(≤${"%.3f".format(activeRatioThreshold)})  " +
                "samples=${levels.sampleCount}"
        )

        // Too short to judge
        if (levels.sampleCount < AUDIO_MIN_SAMPLE_COUNT_FOR_INFERENCE) {
            Log.d(LOG_TAG, "silence-check → silent (too few samples)")
            return true
        }

        // All three criteria must be met to be considered silent
        val isSilent = levels.rmsDbfs <= rmsThreshold &&
            levels.peakDbfs <= peakThreshold &&
            levels.activeRatio <= activeRatioThreshold

        Log.d(LOG_TAG, "silence-check → ${if (isSilent) "silent" else "speech detected"}")
        return isSilent
    }

    private fun buildConversationContext(includeMessageId: Long?): List<ConversationContextTurn> {
        val snapshot = _uiState.value
        if (!snapshot.conversationContextEnabled) {
            return emptyList()
        }
        val maxTurns = snapshot.conversationContextTurns.coerceIn(1, 10)
        return snapshot.messages
            .asSequence()
            .filterNot { message -> isQueuedPlaceholderMessage(message) }
            .filterNot { message -> includeMessageId != null && message.id == includeMessageId }
            .mapNotNull { message ->
                val source = message.sourceText?.trim().orEmpty()
                val translated = message.translatedText.trim()
                if (source.isBlank() || translated.isBlank()) {
                    null
                } else {
                    ConversationContextTurn(
                        sourceLanguage = message.sourceLanguage,
                        targetLanguage = message.targetLanguage,
                        sourceText = source,
                        translatedText = translated
                    )
                }
            }
            .take(maxTurns)
            .toList()
    }

    private fun isNoSpeechMarker(value: String): Boolean {
        val normalized = value.trim().uppercase()
            .replace("[", "")
            .replace("]", "")
            .replace("<", "")
            .replace(">", "")
        return normalized == DIRECT_AUDIO_NO_SPEECH_MARKER
    }

    private fun handleNoSpeechOutput(
        replacingMessageId: Long?,
        previousSourceText: String?,
        previousTranslatedText: String
    ) {
        _uiState.update { current ->
            val cleanedMessages = if (replacingMessageId != null) {
                current.messages.mapNotNull { message ->
                    if (message.id != replacingMessageId) {
                        return@mapNotNull message
                    }
                    if (isQueuedPlaceholderMessage(message) || previousTranslatedText == QUEUED_PLACEHOLDER_TEXT) {
                        null
                    } else {
                        message.copy(
                            sourceText = previousSourceText,
                            translatedText = previousTranslatedText
                        )
                    }
                }
            } else {
                current.messages
            }
            current.copy(
                isTranslating = false,
                streamingSourceText = "",
                streamingTranslatedText = "",
                isModelLoading = false,
                modelLoadProgressPercent = null,
                modelLoadStatusText = "",
                liveCard = null,
                messages = cleanedMessages,
                statusText = "No intelligible speech detected. Skipped translation."
            )
        }
    }

    private fun persist() {
        preferences.saveConfig(_uiState.value)
    }

    private fun triggerAutoModelPreload() {
        val snapshot = _uiState.value
        val modelFileUri = snapshot.modelFileUri?.takeIf { it.isNotBlank() } ?: return
        if (snapshot.isListening || snapshot.isTranslating) return

        val signature = "${snapshot.mode.name}|$modelFileUri"
        val currentJob = modelPreloadJob
        if (currentJob?.isActive == true) {
            if (modelPreloadInFlightSignature == signature) {
                return
            }
            currentJob.cancel()
        }

        if (lastSuccessfulModelPreloadSignature == signature &&
            snapshot.currentRuntimeName.isNotBlank()
        ) {
            return
        }

        modelPreloadInFlightSignature = signature
        modelPreloadJob = viewModelScope.launch {
            try {
                _uiState.update { current ->
                    current.copy(
                        isModelLoading = true,
                        modelLoadProgressPercent = 0,
                        modelLoadStatusText = AUTO_PRELOAD_LOADING_STATUS,
                        statusText = AUTO_PRELOAD_LOADING_STATUS
                    )
                }

                val request = TranslationRequest(
                    transcript = "",
                    audioWavBytes = null,
                    sourceLanguage = snapshot.sourceLanguage,
                    targetLanguage = snapshot.targetLanguage,
                    modelFileUri = modelFileUri,
                    directAudioPromptSkipNoSpeech = snapshot.directAudioPromptSkipNoSpeech,
                    directAudioSilenceSensitivity = snapshot.directAudioSilenceSensitivity,
                    conversationContext = emptyList()
                )
                val preloadOutput = try {
                    val statusUpdater: (YDKK.kanuka.engine.RuntimeStatusUpdate) -> Unit = { status ->
                        _uiState.update { current ->
                            current.copy(
                                isModelLoading = status.isModelLoading,
                                modelLoadProgressPercent = status.modelLoadProgressPercent
                                    ?: current.modelLoadProgressPercent,
                                modelLoadStatusText = status.message,
                                statusText = status.message
                            )
                        }
                    }
                    engineFactory.forMode(snapshot.mode).preload(
                        request = request,
                        onStatusUpdate = statusUpdater
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    ModelPreloadOutput(
                        success = false,
                        statusMessage = "Auto model preload failed: " +
                            (error.message ?: error.javaClass.simpleName),
                        runtimeName = null
                    )
                }

                _uiState.update { current ->
                    val runtimeName = preloadOutput.runtimeName.orEmpty()
                    val runtimeBackend = if (runtimeName.isBlank()) {
                        current.currentRuntimeBackend
                    } else {
                        "GPU"
                    }
                    current.copy(
                        isModelLoading = false,
                        modelLoadProgressPercent = if (preloadOutput.success) 100 else null,
                        modelLoadStatusText = preloadOutput.statusMessage,
                        statusText = preloadOutput.statusMessage,
                        currentRuntimeName = runtimeName.ifBlank {
                            current.currentRuntimeName
                        },
                        currentRuntimeBackend = runtimeBackend
                    )
                }
                if (preloadOutput.success) {
                    lastSuccessfulModelPreloadSignature = signature
                }
            } finally {
                if (modelPreloadInFlightSignature == signature) {
                    modelPreloadInFlightSignature = null
                }
            }
        }
    }

    private fun fallbackTarget(source: AppLanguage, candidates: List<AppLanguage>? = null): AppLanguage {
        val list = candidates ?: _uiState.value.speechRecognizerSupportedLanguages
        val firstDifferent = list.firstOrNull {
            !it.localeTag.equals(source.localeTag, ignoreCase = true)
        }
        return firstDifferent ?: AppLanguage.ENGLISH
    }

    private fun fallbackSource(target: AppLanguage): AppLanguage {
        val list = _uiState.value.speechRecognizerSupportedLanguages
        val firstDifferent = list.firstOrNull {
            !it.localeTag.equals(target.localeTag, ignoreCase = true)
        }
        return firstDifferent ?: AppLanguage.JAPANESE
    }

    private fun languageForRole(
        role: InputRole,
        sourceLanguage: AppLanguage,
        targetLanguage: AppLanguage
    ): AppLanguage {
        return when (role) {
            InputRole.SOURCE -> sourceLanguage
            InputRole.TARGET -> targetLanguage
        }
    }

    private fun sourceLanguageForRole(
        role: InputRole?,
        sourceLanguage: AppLanguage,
        targetLanguage: AppLanguage
    ): AppLanguage {
        return when (role) {
            InputRole.SOURCE, null -> sourceLanguage
            InputRole.TARGET -> targetLanguage
        }
    }

    private fun targetLanguageForRole(
        role: InputRole?,
        sourceLanguage: AppLanguage,
        targetLanguage: AppLanguage
    ): AppLanguage {
        return when (role) {
            InputRole.SOURCE, null -> targetLanguage
            InputRole.TARGET -> sourceLanguage
        }
    }

    private fun isDirectGemmaAudioMode(mode: TranslationMode): Boolean {
        return mode == TranslationMode.GEMMA_3N_E4B_LITERTLM_DIRECT_AUDIO
    }

    private data class DirectAudioStreamingPartial(
        val recognizedText: String,
        val translatedText: String
    )

    private data class AudioLevelStats(
        val sampleCount: Int,
        val rmsDbfs: Double,
        val peakDbfs: Double,
        val activeRatio: Double
    )

    private class QueuedTranslationWork(
        val execute: () -> Unit
    )

    private companion object {
        private const val LOG_TAG = "KanukaViewModel"
        private const val MAX_HISTORY_COUNT = 300
        private const val STOP_FINAL_DUPLICATE_WINDOW_MS = 10_000L
        private const val STOP_FINALIZE_DEBOUNCE_MS = 280L
        private const val DUPLICATE_MESSAGE_WINDOW_MS = 15_000L
        private const val STREAMING_UI_UPDATE_INTERVAL_MS = 80L
        private const val STREAMING_FORCE_UPDATE_CHAR_DELTA = 24
        private const val STREAMING_FORCE_UPDATE_TERMINATORS = ".!?\n"
        private const val AUTO_PRELOAD_LOADING_STATUS = "Auto-loading Gemma 3n model..."
        private const val WAV_HEADER_BYTES = 44
        private const val PCM_16_FULL_SCALE = 32768.0
        private const val AUDIO_DB_EPSILON = 1e-9
        private const val AUDIO_ACTIVE_SAMPLE_THRESHOLD = 0.2
        private const val AUDIO_MIN_SAMPLE_COUNT_FOR_INFERENCE = 1600
        // Silence thresholds: lerp(LENIENT, STRICT, sensitivity/100)
        // sensitivity=0 → lenient (only very quiet audio is silent)
        // sensitivity=100 → strict (louder audio still classified as silent)
        private const val SILENCE_RMS_DBFS_LENIENT = -28.0
        private const val SILENCE_RMS_DBFS_STRICT = -18.0
        private const val SILENCE_PEAK_DBFS_LENIENT = -22.0
        private const val SILENCE_PEAK_DBFS_STRICT = -13.0
        private const val SILENCE_ACTIVE_RATIO_LENIENT = 0.02
        private const val SILENCE_ACTIVE_RATIO_STRICT = 0.14
        private const val QUEUED_PLACEHOLDER_TEXT = "Queued..."
        private const val DIRECT_AUDIO_NO_SPEECH_MARKER = "NO_SPEECH"
    }
}
