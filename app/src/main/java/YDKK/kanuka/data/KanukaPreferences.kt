package YDKK.kanuka.data

import YDKK.kanuka.model.AppLanguage
import YDKK.kanuka.model.KanukaUiState
import YDKK.kanuka.model.TranslationMode
import android.content.Context
import androidx.core.content.edit

class KanukaPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadConfig(): StoredConfig {
        val rawMode = prefs.getString(KEY_MODE, null)
        val mode = when {
            rawMode == null -> TranslationMode.GEMMA_3N_E4B_LITERTLM_SPEECH_RECOGNIZER
            else -> rawMode.toEnumOrNull<TranslationMode>() ?: TranslationMode.GEMMA_3N_E4B_LITERTLM_SPEECH_RECOGNIZER
        }
        val source = prefs.getString(KEY_SOURCE_LANGUAGE_TAG, null)
            ?.let { tag -> AppLanguage.fromLocaleTag(tag) }
            ?: AppLanguage.JAPANESE
        val target = prefs.getString(KEY_TARGET_LANGUAGE_TAG, null)
            ?.let { tag -> AppLanguage.fromLocaleTag(tag) }
            ?: AppLanguage.ENGLISH
        val modelFileUri = prefs.getString(KEY_MODEL_FILE_URI, null)
        val directAudioSilenceSensitivity = prefs.getInt(KEY_DIRECT_AUDIO_SILENCE_SENSITIVITY, 50)
            .coerceIn(0, 100)
        val directAudioPromptSkipNoSpeech = prefs.getBoolean(KEY_DIRECT_AUDIO_PROMPT_SKIP_NO_SPEECH, false)
        val speechRecognizerSupportedLanguages = prefs.getString(KEY_SPEECH_RECOGNIZER_SUPPORTED_LANGUAGES, null)
            ?.split(',')
            ?.map { token -> token.trim() }
            ?.filter { token -> token.isNotBlank() }
            ?.map { tag -> AppLanguage.fromLocaleTag(tag) }
            ?.distinctBy { language -> language.localeTag.lowercase() }
            ?.takeIf { it.isNotEmpty() }
            ?: AppLanguage.defaults()
        val visibleLanguageTags = prefs.getString(KEY_VISIBLE_LANGUAGE_TAGS, null)
            ?.split(',')
            ?.map { token -> token.trim() }
            ?.filter { token -> token.isNotBlank() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: speechRecognizerSupportedLanguages.map { language -> language.localeTag }.toSet()
        val conversationContextEnabled = prefs.getBoolean(KEY_CONVERSATION_CONTEXT_ENABLED, false)
        val conversationContextTurns = prefs.getInt(KEY_CONVERSATION_CONTEXT_TURNS, 3)
            .coerceIn(1, 10)
        return StoredConfig(
            mode = mode,
            sourceLanguage = source,
            targetLanguage = if (source == target) {
                speechRecognizerSupportedLanguages.firstOrNull {
                    !it.localeTag.equals(source.localeTag, ignoreCase = true)
                } ?: AppLanguage.ENGLISH
            } else {
                target
            },
            modelFileUri = modelFileUri,
            directAudioSilenceSensitivity = directAudioSilenceSensitivity,
            directAudioPromptSkipNoSpeech = directAudioPromptSkipNoSpeech,
            speechRecognizerSupportedLanguages = speechRecognizerSupportedLanguages,
            visibleLanguageTags = visibleLanguageTags,
            conversationContextEnabled = conversationContextEnabled,
            conversationContextTurns = conversationContextTurns
        )
    }

    fun saveConfig(uiState: KanukaUiState) {
        prefs.edit {
            putString(KEY_MODE, uiState.mode.name)
            putString(KEY_SOURCE_LANGUAGE_TAG, uiState.sourceLanguage.localeTag)
            putString(KEY_TARGET_LANGUAGE_TAG, uiState.targetLanguage.localeTag)
            putString(KEY_MODEL_FILE_URI, uiState.modelFileUri)
            putInt(KEY_DIRECT_AUDIO_SILENCE_SENSITIVITY, uiState.directAudioSilenceSensitivity.coerceIn(0, 100))
            putBoolean(KEY_DIRECT_AUDIO_PROMPT_SKIP_NO_SPEECH, uiState.directAudioPromptSkipNoSpeech)
            putString(
                KEY_SPEECH_RECOGNIZER_SUPPORTED_LANGUAGES,
                uiState.speechRecognizerSupportedLanguages
                    .ifEmpty { AppLanguage.defaults() }
                    .joinToString(",") { language -> language.localeTag }
            )
            putString(
                KEY_VISIBLE_LANGUAGE_TAGS,
                uiState.visibleLanguageTags
                    .ifEmpty {
                        uiState.speechRecognizerSupportedLanguages
                            .ifEmpty { AppLanguage.defaults() }
                            .map { language -> language.localeTag }
                            .toSet()
                    }
                    .joinToString(",")
            )
            putBoolean(KEY_CONVERSATION_CONTEXT_ENABLED, uiState.conversationContextEnabled)
            putInt(KEY_CONVERSATION_CONTEXT_TURNS, uiState.conversationContextTurns.coerceIn(1, 10))
        }
    }

    private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? {
        return this?.let { value ->
            enumValues<T>().firstOrNull { it.name == value }
        }
    }

    data class StoredConfig(
        val mode: TranslationMode,
        val sourceLanguage: AppLanguage,
        val targetLanguage: AppLanguage,
        val modelFileUri: String?,
        val directAudioSilenceSensitivity: Int,
        val directAudioPromptSkipNoSpeech: Boolean,
        val speechRecognizerSupportedLanguages: List<AppLanguage>,
        val visibleLanguageTags: Set<String>,
        val conversationContextEnabled: Boolean,
        val conversationContextTurns: Int
    )

    private companion object {
        private const val PREFS_NAME = "kanuka_preferences"
        private const val KEY_MODE = "mode"
        private const val KEY_SOURCE_LANGUAGE_TAG = "source_language_tag"
        private const val KEY_TARGET_LANGUAGE_TAG = "target_language_tag"
        private const val KEY_MODEL_FILE_URI = "model_file_uri"
        private const val KEY_DIRECT_AUDIO_SILENCE_SENSITIVITY = "direct_audio_silence_sensitivity"
        private const val KEY_DIRECT_AUDIO_PROMPT_SKIP_NO_SPEECH = "direct_audio_prompt_skip_no_speech"
        private const val KEY_SPEECH_RECOGNIZER_SUPPORTED_LANGUAGES = "speech_recognizer_supported_languages"
        private const val KEY_VISIBLE_LANGUAGE_TAGS = "visible_language_tags"
        private const val KEY_CONVERSATION_CONTEXT_ENABLED = "conversation_context_enabled"
        private const val KEY_CONVERSATION_CONTEXT_TURNS = "conversation_context_turns"
    }
}
