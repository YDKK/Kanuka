package YDKK.kanuka.model

import java.util.Locale

data class AppLanguage(
    val code: String,
    val displayName: String,
    val localeTag: String
) {
    companion object {
        val JAPANESE = AppLanguage(code = "JA", displayName = "Japanese", localeTag = "ja-JP")
        val ENGLISH = AppLanguage(code = "EN", displayName = "English", localeTag = "en-US")
        val CHINESE = AppLanguage(code = "ZH", displayName = "Chinese", localeTag = "zh-CN")
        val KOREAN = AppLanguage(code = "KO", displayName = "Korean", localeTag = "ko-KR")
        val THAI = AppLanguage(code = "TH", displayName = "Thai", localeTag = "th-TH")
        val VIETNAMESE = AppLanguage(code = "VI", displayName = "Vietnamese", localeTag = "vi-VN")
        val FILIPINO = AppLanguage(code = "FIL", displayName = "Filipino", localeTag = "fil-PH")
        val GERMAN = AppLanguage(code = "DE", displayName = "German", localeTag = "de-DE")
        val SPANISH = AppLanguage(code = "ES", displayName = "Spanish", localeTag = "es-ES")
        val FRENCH = AppLanguage(code = "FR", displayName = "French", localeTag = "fr-FR")
        val ITALIAN = AppLanguage(code = "IT", displayName = "Italian", localeTag = "it-IT")
        val HINDI = AppLanguage(code = "HI", displayName = "Hindi", localeTag = "hi-IN")

        fun defaults(): List<AppLanguage> = listOf(
            JAPANESE,
            ENGLISH,
            CHINESE,
            KOREAN,
            THAI,
            VIETNAMESE,
            FILIPINO,
            GERMAN,
            SPANISH,
            FRENCH,
            ITALIAN,
            HINDI
        )

        fun fromLocaleTag(tag: String): AppLanguage {
            val normalizedTag = tag.trim().ifBlank { "en-US" }
            defaults().firstOrNull {
                it.localeTag.equals(normalizedTag, ignoreCase = true)
            }?.let { return it }

            val languageOnly = Locale.forLanguageTag(normalizedTag).language
            defaults().firstOrNull {
                Locale.forLanguageTag(it.localeTag).language.equals(languageOnly, ignoreCase = true)
            }?.let { return it }

            val locale = Locale.forLanguageTag(normalizedTag)
            val languageCode = locale.language.ifBlank { "en" }.uppercase()
            val languageName = locale.getDisplayLanguage(Locale.ENGLISH)
                .ifBlank { normalizedTag }
            val display = languageName.replaceFirstChar { it.uppercase() }
            return AppLanguage(
                code = languageCode,
                displayName = display,
                localeTag = normalizedTag
            )
        }
    }
}

enum class TranslationMode(
    val title: String,
    val description: String
) {
    GEMMA_3N_E4B_LITERTLM_SPEECH_RECOGNIZER(
        title = "Gemma 3n E4B + SpeechRecognizer",
        description = "SpeechRecognizer STT + LiteRT-LM with Gemma 3n E4B."
    ),
    GEMMA_3N_E4B_LITERTLM_DIRECT_AUDIO(
        title = "Gemma 3n E4B Direct Audio",
        description = "Direct mic audio + LiteRT-LM with Gemma 3n E4B."
    )
}

enum class AppScreen {
    MAIN,
    SETTINGS,
    SPEECH_LANGUAGE_SETTINGS,
    OSS_LICENSES
}

enum class InputRole {
    SOURCE,
    TARGET
}

enum class LiveCardPhase {
    RECOGNIZING,
    RECOGNIZED,
    TRANSLATING
}

data class LiveTranslationCard(
    val id: Long,
    val mode: TranslationMode,
    val sourceLanguage: AppLanguage,
    val targetLanguage: AppLanguage,
    val sourceText: String,
    val translatedText: String = "",
    val phase: LiveCardPhase
)

data class ChatMessage(
    val id: Long,
    val mode: TranslationMode,
    val sourceLanguage: AppLanguage,
    val targetLanguage: AppLanguage,
    val sourceText: String?,
    val translatedText: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class ConversationContextTurn(
    val sourceLanguage: AppLanguage,
    val targetLanguage: AppLanguage,
    val sourceText: String,
    val translatedText: String
)

data class KanukaUiState(
    val currentScreen: AppScreen = AppScreen.MAIN,
    val sourceLanguage: AppLanguage = AppLanguage.JAPANESE,
    val targetLanguage: AppLanguage = AppLanguage.ENGLISH,
    val mode: TranslationMode = TranslationMode.GEMMA_3N_E4B_LITERTLM_SPEECH_RECOGNIZER,
    val isListening: Boolean = false,
    val activeInputRole: InputRole? = null,
    val lastInputRole: InputRole? = null,
    val partialTranscript: String = "",
    val isTranslating: Boolean = false,
    val streamingSourceText: String = "",
    val streamingTranslatedText: String = "",
    val isModelLoading: Boolean = false,
    val modelLoadProgressPercent: Int? = null,
    val modelLoadStatusText: String = "",
    val modelFileUri: String? = null,
    val directAudioSilenceSensitivity: Int = 50,
    val directAudioPromptSkipNoSpeech: Boolean = false,
    val speechRecognizerSupportedLanguages: List<AppLanguage> = AppLanguage.defaults(),
    val visibleLanguageTags: Set<String> = AppLanguage.defaults().map { it.localeTag }.toSet(),
    val conversationContextEnabled: Boolean = false,
    val conversationContextTurns: Int = 3,
    val statusText: String = "Set model file and start microphone.",
    val currentRuntimeBackend: String = "Not initialized",
    val currentRuntimeName: String = "",
    val liveCard: LiveTranslationCard? = null,
    val messages: List<ChatMessage> = emptyList()
)
