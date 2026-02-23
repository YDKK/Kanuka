package YDKK.kanuka.engine

import YDKK.kanuka.model.AppLanguage
import YDKK.kanuka.model.ConversationContextTurn
import YDKK.kanuka.model.TranslationMode
import android.content.Context
import android.util.Log

data class TranslationRequest(
    val transcript: String,
    val audioWavBytes: ByteArray? = null,
    val sourceLanguage: AppLanguage,
    val targetLanguage: AppLanguage,
    val modelFileUri: String?,
    val directAudioPromptSkipNoSpeech: Boolean = false,
    val directAudioSilenceSensitivity: Int = 50,
    val conversationContext: List<ConversationContextTurn> = emptyList()
)

data class TranslationOutput(
    val sourceText: String?,
    val translatedText: String,
    val statusMessage: String,
    val runtimeName: String? = null
)

data class ModelPreloadOutput(
    val success: Boolean,
    val statusMessage: String,
    val runtimeName: String? = null
)

interface TranslationEngine {
    suspend fun translate(
        request: TranslationRequest,
        onPartialResult: ((String) -> Unit)? = null,
        onStatusUpdate: ((RuntimeStatusUpdate) -> Unit)? = null
    ): TranslationOutput

    suspend fun preload(
        request: TranslationRequest,
        onStatusUpdate: ((RuntimeStatusUpdate) -> Unit)? = null
    ): ModelPreloadOutput

    fun cancel()
}

class TranslationEngineFactory(
    context: Context
) {
    private val appContext = context.applicationContext
    private val modelResolver = ModelResolver(appContext)
    private val liteRtLmRuntimeBridge: RuntimeBridge = LiteRtLmRuntimeBridge(appContext)

    fun forMode(mode: TranslationMode): TranslationEngine {
        return when (mode) {
            TranslationMode.GEMMA_3N_E4B_LITERTLM_SPEECH_RECOGNIZER -> Gemma3nE4bSpeechRecognizerEngine(
                modelResolver,
                liteRtLmRuntimeBridge
            )

            TranslationMode.GEMMA_3N_E4B_LITERTLM_DIRECT_AUDIO -> Gemma3nE4bDirectAudioEngine(
                modelResolver,
                liteRtLmRuntimeBridge
            )
        }
    }
}

private class Gemma3nE4bSpeechRecognizerEngine(
    modelResolver: ModelResolver,
    runtimeBridge: RuntimeBridge
) : ModelBackedEngine(
    mode = TranslationMode.GEMMA_3N_E4B_LITERTLM_SPEECH_RECOGNIZER,
    engineName = "Gemma 3n E4B SpeechRecognizer",
    requiredModelsProvider = { setOf(ModelSlot.GEMMA_3N_E4B_LITERTLM) },
    emitsSourceText = true,
    modelResolver = modelResolver,
    runtimeBridge = runtimeBridge
)

private class Gemma3nE4bDirectAudioEngine(
    modelResolver: ModelResolver,
    runtimeBridge: RuntimeBridge
) : ModelBackedEngine(
    mode = TranslationMode.GEMMA_3N_E4B_LITERTLM_DIRECT_AUDIO,
    engineName = "Gemma 3n E4B Direct Audio",
    requiredModelsProvider = { setOf(ModelSlot.GEMMA_3N_E4B_LITERTLM) },
    emitsSourceText = true,
    modelResolver = modelResolver,
    runtimeBridge = runtimeBridge
)

private abstract class ModelBackedEngine(
    private val mode: TranslationMode,
    private val engineName: String,
    private val requiredModelsProvider: (TranslationRequest) -> Set<ModelSlot>,
    private val emitsSourceText: Boolean,
    private val modelResolver: ModelResolver,
    private val runtimeBridge: RuntimeBridge
) : TranslationEngine {

    override suspend fun translate(
        request: TranslationRequest,
        onPartialResult: ((String) -> Unit)?,
        onStatusUpdate: ((RuntimeStatusUpdate) -> Unit)?
    ): TranslationOutput {
        val resolution = modelResolver.resolve(request.modelFileUri)
        return when (resolution) {
            is ModelResolutionResult.Error -> failureOutput(
                request = request,
                status = resolution.message
            )

            is ModelResolutionResult.Ready -> {
                val requiredModels = requiredModelsProvider(request)
                val missing = requiredModels.filter { slot -> resolution.models.uriFor(slot) == null }
                if (missing.isNotEmpty()) {
                    val missingText = missing.joinToString { slot -> slot.displayName }
                    failureOutput(
                        request = request,
                        status = "missing required models ($missingText). " +
                            "Select the required .litertlm model file in Settings."
                    )
                } else {
                    when (
                        val runtime = runtimeBridge.translate(
                            mode = mode,
                            request = request,
                            models = resolution.models,
                            onPartialResult = onPartialResult,
                            onStatusUpdate = onStatusUpdate
                        )
                    ) {
                        is RuntimeAttempt.Success -> {
                            TranslationOutput(
                                sourceText = if (emitsSourceText) runtime.result.sourceText else null,
                                translatedText = runtime.result.translatedText,
                                statusMessage = "Runtime active.",
                                runtimeName = runtime.result.runtimeName
                            )
                        }

                        is RuntimeAttempt.Failed -> failureOutput(
                            request = request,
                            status = "Runtime failed: ${runtime.reason}"
                        )

                        is RuntimeAttempt.NotAvailable -> failureOutput(
                            request = request,
                            status = "Runtime unavailable: ${runtime.reason}"
                        )
                    }
                }
            }
        }
    }

    override suspend fun preload(
        request: TranslationRequest,
        onStatusUpdate: ((RuntimeStatusUpdate) -> Unit)?
    ): ModelPreloadOutput {
        val resolution = modelResolver.resolve(request.modelFileUri)
        return when (resolution) {
            is ModelResolutionResult.Error -> failurePreloadOutput(
                status = resolution.message
            )

            is ModelResolutionResult.Ready -> {
                val requiredModels = requiredModelsProvider(request)
                val missing = requiredModels.filter { slot -> resolution.models.uriFor(slot) == null }
                if (missing.isNotEmpty()) {
                    val missingText = missing.joinToString { slot -> slot.displayName }
                    failurePreloadOutput(
                        status = "Missing required models ($missingText). " +
                            "Select the required .litertlm model file in Settings."
                    )
                } else {
                    when (
                        val runtime = runtimeBridge.preload(
                            mode = mode,
                            request = request,
                            models = resolution.models,
                            onStatusUpdate = onStatusUpdate
                        )
                    ) {
                        is RuntimePreloadAttempt.Success -> {
                            ModelPreloadOutput(
                                success = true,
                                statusMessage = "Runtime active.",
                                runtimeName = runtime.runtimeName
                            )
                        }

                        is RuntimePreloadAttempt.Failed -> failurePreloadOutput(
                            status = "Preload failed: ${runtime.reason}"
                        )

                        is RuntimePreloadAttempt.NotAvailable -> failurePreloadOutput(
                            status = "Runtime unavailable: ${runtime.reason}"
                        )
                    }
                }
            }
        }
    }

    override fun cancel() {
        runtimeBridge.cancel(mode)
    }

    private fun failureOutput(
        request: TranslationRequest,
        status: String
    ): TranslationOutput {
        Log.e(LOG_TAG, status)
        return TranslationOutput(
            sourceText = if (emitsSourceText) request.transcript else null,
            translatedText = "",
            statusMessage = status,
            runtimeName = null
        )
    }

    private fun failurePreloadOutput(
        status: String
    ): ModelPreloadOutput {
        Log.e(LOG_TAG, status)
        return ModelPreloadOutput(
            success = false,
            statusMessage = status,
            runtimeName = null
        )
    }
}

private const val LOG_TAG = "TranslationEngine"

internal data class RuntimeResult(
    val sourceText: String?,
    val translatedText: String,
    val runtimeName: String
)

data class RuntimeStatusUpdate(
    val message: String,
    val isModelLoading: Boolean = false,
    val modelLoadProgressPercent: Int? = null
)

internal sealed interface RuntimeAttempt {
    data class Success(val result: RuntimeResult) : RuntimeAttempt
    data class Failed(val reason: String) : RuntimeAttempt
    data class NotAvailable(val reason: String) : RuntimeAttempt
}

internal interface RuntimeBridge {
    suspend fun translate(
        mode: TranslationMode,
        request: TranslationRequest,
        models: ResolvedModels,
        onPartialResult: ((String) -> Unit)? = null,
        onStatusUpdate: ((RuntimeStatusUpdate) -> Unit)? = null
    ): RuntimeAttempt

    suspend fun preload(
        mode: TranslationMode,
        request: TranslationRequest,
        models: ResolvedModels,
        onStatusUpdate: ((RuntimeStatusUpdate) -> Unit)? = null
    ): RuntimePreloadAttempt

    fun cancel(mode: TranslationMode)
}

internal sealed interface RuntimePreloadAttempt {
    data class Success(val runtimeName: String) : RuntimePreloadAttempt
    data class Failed(val reason: String) : RuntimePreloadAttempt
    data class NotAvailable(val reason: String) : RuntimePreloadAttempt
}
