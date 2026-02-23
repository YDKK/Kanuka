package YDKK.kanuka.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechRecognizerManager(
    context: Context,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var shouldListenContinuously = false
    private var languageCandidates: List<String> = listOf("ja-JP")
    private var languageCandidateIndex: Int = 0
    private var restartRunnable: Runnable? = null
    private var consecutiveServerDisconnects: Int = 0

    init {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onStatusChanged("Speech recognition service is not available.")
        } else {
            recreateRecognizer()
        }
    }

    fun startListening(localeTag: String) {
        if (recognizer == null) {
            onStatusChanged("Speech recognition service is unavailable.")
            return
        }

        languageCandidates = resolveLanguageCandidates(localeTag)
        languageCandidateIndex = 0
        shouldListenContinuously = true
        consecutiveServerDisconnects = 0
        cancelRestart()
        recognizer?.cancel()
        startListeningInternal()
    }

    fun stopListening() {
        shouldListenContinuously = false
        cancelRestart()
        recognizer?.stopListening()
    }

    fun destroy() {
        shouldListenContinuously = false
        cancelRestart()
        destroyRecognizer()
    }

    private fun startListeningInternal() {
        if (!shouldListenContinuously) return
        val recognizer = recognizer ?: return
        val selectedLanguage = currentLanguageTag()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, selectedLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            recognizer.startListening(intent)
        } catch (error: Exception) {
            onStatusChanged("Failed to start recognition: ${error.message ?: "unknown error"}")
            scheduleRestart(400L)
        }
    }

    private fun scheduleRestart(delayMillis: Long) {
        if (!shouldListenContinuously) return
        cancelRestart()
        restartRunnable = Runnable {
            startListeningInternal()
        }
        mainHandler.postDelayed(restartRunnable!!, delayMillis)
    }

    private fun cancelRestart() {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
    }

    private fun extractBestText(bundle: Bundle?): String {
        return bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
            .trim()
    }

    private fun currentLanguageTag(): String {
        return languageCandidates.getOrElse(languageCandidateIndex) {
            languageCandidates.firstOrNull() ?: "ja-JP"
        }
    }

    private fun resolveLanguageCandidates(localeTag: String): List<String> {
        val normalized = localeTag.trim()
        if (normalized.equals("zh-CN", ignoreCase = true) ||
            normalized.equals("zh-Hans-CN", ignoreCase = true) ||
            normalized.equals("cmn-Hans-CN", ignoreCase = true)
        ) {
            return listOf(
                "cmn-Hans-CN",
                "zh-Hans-CN",
                "zh-CN",
                "cmn-CN"
            )
        }
        return listOf(normalized)
    }

    private fun shouldRetryWithAlternativeLanguage(error: Int): Boolean {
        if (error != SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) return false
        if (languageCandidateIndex + 1 >= languageCandidates.size) return false
        languageCandidateIndex += 1
        onStatusChanged(
            "Language unavailable. Retrying with ${currentLanguageTag()}..."
        )
        return true
    }

    private fun errorToMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio input error."
            SpeechRecognizer.ERROR_CLIENT -> "Recognizer client error."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing microphone permission."
            SpeechRecognizer.ERROR_NETWORK -> "Network error (offline mode preferred)."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout."
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy, retrying."
            SpeechRecognizer.ERROR_SERVER -> "Recognition server error."
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Recognition server disconnected. Reconnecting..."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout."
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable."
            else -> "Recognition error: $error"
        }
    }

    private fun shouldRetry(error: Int): Boolean {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> true
            SpeechRecognizer.ERROR_CLIENT -> true
            SpeechRecognizer.ERROR_NETWORK -> true
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> true
            SpeechRecognizer.ERROR_NO_MATCH -> true
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> true
            SpeechRecognizer.ERROR_SERVER -> true
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> true
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> true
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> false
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> false
            else -> true
        }
    }

    private fun recreateRecognizer() {
        destroyRecognizer()
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onStatusChanged("Listening...")
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    scheduleRestart(180L)
                }

                override fun onError(error: Int) {
                    if (isServerDisconnectedError(error)) {
                        consecutiveServerDisconnects += 1
                        recreateRecognizer()
                        val backoff = (250L * consecutiveServerDisconnects.coerceAtMost(6))
                            .coerceAtMost(1800L)
                        onStatusChanged(errorToMessage(error))
                        scheduleRestart(backoff)
                        return
                    }
                    consecutiveServerDisconnects = 0
                    if (shouldRetryWithAlternativeLanguage(error)) {
                        scheduleRestart(120L)
                        return
                    }
                    onStatusChanged(errorToMessage(error))
                    if (shouldRetry(error)) {
                        scheduleRestart(300L)
                    }
                }

                override fun onResults(results: Bundle?) {
                    consecutiveServerDisconnects = 0
                    val finalText = extractBestText(results)
                    if (finalText.isNotBlank()) {
                        onFinalResult(finalText)
                    }
                    scheduleRestart(120L)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partialText = extractBestText(partialResults)
                    if (partialText.isNotBlank()) {
                        onPartialResult(partialText)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun isServerDisconnectedError(error: Int): Boolean {
        return error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == 11
    }
}
