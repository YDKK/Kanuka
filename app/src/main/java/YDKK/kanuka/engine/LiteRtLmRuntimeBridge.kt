package YDKK.kanuka.engine

import YDKK.kanuka.model.TranslationMode
import YDKK.kanuka.model.ConversationContextTurn
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class LiteRtLmRuntimeBridge(
    private val context: Context
) : RuntimeBridge {
    private val appContext = context.applicationContext
    private val logTag = "LiteRtLmRuntimeBridge"

    override suspend fun translate(
        mode: TranslationMode,
        request: TranslationRequest,
        models: ResolvedModels,
        onPartialResult: ((String) -> Unit)?,
        onStatusUpdate: ((RuntimeStatusUpdate) -> Unit)?
    ): RuntimeAttempt = withContext(Dispatchers.IO) {
        try {
            val modelTarget = when (val resolution = resolveModelTarget(mode, models)) {
                is ModelTargetResolution.NotAvailable -> {
                    return@withContext RuntimeAttempt.NotAvailable(resolution.reason)
                }

                is ModelTargetResolution.Ready -> resolution.target
            }

            val directAudioBytes = if (mode == TranslationMode.GEMMA_3N_E4B_LITERTLM_DIRECT_AUDIO) {
                request.audioWavBytes
            } else {
                null
            }
            if (
                mode == TranslationMode.GEMMA_3N_E4B_LITERTLM_DIRECT_AUDIO &&
                directAudioBytes == null &&
                request.transcript.isBlank()
            ) {
                return@withContext RuntimeAttempt.Failed(
                    "Gemma 3n direct audio mode requires captured WAV input " +
                        "or non-empty transcript for retranslation."
                )
            }

            val signature = buildEngineSignature(modelTarget)
            ensureEngineLoaded(
                signature = signature,
                modelUri = modelTarget.uri,
                backendPlans = modelTarget.backendPlans,
                onStatusUpdate = onStatusUpdate
            )?.let { failure ->
                return@withContext failure
            }

            var generation = synchronized(ENGINE_LOCK) {
                val activeEngine = engine
                if (activeEngine == null) {
                    GenerationAttempt.Failed("LiteRT-LM engine is not initialized.")
                } else {
                    runGeneration(
                        activeEngine = activeEngine,
                        request = request,
                        directAudioBytes = directAudioBytes,
                        onPartialResult = onPartialResult,
                        onStatusUpdate = onStatusUpdate
                    )
                }
            }

            val rawOutput = when (generation) {
                is GenerationAttempt.Failed -> return@withContext RuntimeAttempt.Failed(generation.reason)
                is GenerationAttempt.Success -> generation.text
            }

            val directAudioParsed = if (directAudioBytes != null) {
                parseDirectAudioOutput(rawOutput)
            } else {
                null
            }
            val translated = directAudioParsed?.translatedText ?: rawOutput
            if (translated.isBlank()) {
                return@withContext RuntimeAttempt.Failed(
                    "LiteRT-LM runtime returned empty result. " +
                        "backend=${activeBackendLabel()}"
                )
            }

            return@withContext RuntimeAttempt.Success(
                RuntimeResult(
                    sourceText = directAudioParsed?.recognizedText?.takeIf { it.isNotBlank() }
                        ?: request.transcript,
                    translatedText = translated,
                    runtimeName = "litertlm-${modelTarget.runtimeModelId}-" +
                        loadedBackend.name.lowercase()
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return@withContext RuntimeAttempt.Failed(
                "LiteRT-LM runtime exception: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    override suspend fun preload(
        mode: TranslationMode,
        request: TranslationRequest,
        models: ResolvedModels,
        onStatusUpdate: ((RuntimeStatusUpdate) -> Unit)?
    ): RuntimePreloadAttempt = withContext(Dispatchers.IO) {
        try {
            val modelTarget = when (val resolution = resolveModelTarget(mode, models)) {
                is ModelTargetResolution.NotAvailable -> {
                    return@withContext RuntimePreloadAttempt.NotAvailable(resolution.reason)
                }

                is ModelTargetResolution.Ready -> resolution.target
            }
            val signature = buildEngineSignature(modelTarget)
            ensureEngineLoaded(
                signature = signature,
                modelUri = modelTarget.uri,
                backendPlans = modelTarget.backendPlans,
                onStatusUpdate = onStatusUpdate
            )?.let { failure ->
                return@withContext when (failure) {
                    is RuntimeAttempt.Failed -> RuntimePreloadAttempt.Failed(failure.reason)
                    is RuntimeAttempt.NotAvailable -> RuntimePreloadAttempt.NotAvailable(failure.reason)
                    is RuntimeAttempt.Success -> RuntimePreloadAttempt.Failed(
                        "Unexpected runtime state while preloading."
                    )
                }
            }

            return@withContext RuntimePreloadAttempt.Success(
                runtimeName = "litertlm-${modelTarget.runtimeModelId}-" +
                    loadedBackend.name.lowercase()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return@withContext RuntimePreloadAttempt.Failed(
                "LiteRT-LM preload exception: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    override fun cancel(mode: TranslationMode) {
        val active = synchronized(ENGINE_LOCK) { activeConversation }
        if (active == null) {
            return
        }
        Log.i(logTag, "Cancelling active LiteRT-LM conversation for mode=${mode.name}.")
        runCatching { active.cancelProcess() }
            .onFailure { error ->
                Log.w(
                    logTag,
                    "LiteRT-LM cancelProcess failed: ${error.message ?: error.javaClass.simpleName}"
                )
            }
    }

    private fun resolveModelTarget(
        mode: TranslationMode,
        models: ResolvedModels
    ): ModelTargetResolution {
        return when (mode) {
            TranslationMode.GEMMA_3N_E4B_LITERTLM_SPEECH_RECOGNIZER -> {
                val uri = models.uriFor(ModelSlot.GEMMA_3N_E4B_LITERTLM)
                    ?: return ModelTargetResolution.NotAvailable(
                        "LiteRT-LM Gemma 3n E4B model is missing. " +
                            "Select gemma-3n-E4B-it-int4.litertlm in Settings."
                    )
                ModelTargetResolution.Ready(
                    ModelTarget(
                        uri = uri,
                        runtimeModelId = "gemma3n-e4b",
                        signatureModelId = "gemma3n-e4b",
                        backendPlans = GEMMA3N_E4B_BACKEND_PLANS
                    )
                )
            }

            TranslationMode.GEMMA_3N_E4B_LITERTLM_DIRECT_AUDIO -> {
                val uri = models.uriFor(ModelSlot.GEMMA_3N_E4B_LITERTLM)
                    ?: return ModelTargetResolution.NotAvailable(
                        "LiteRT-LM Gemma 3n E4B model is missing. " +
                            "Select gemma-3n-E4B-it-int4.litertlm in Settings."
                    )
                ModelTargetResolution.Ready(
                    ModelTarget(
                        uri = uri,
                        runtimeModelId = "gemma3n-e4b-direct-audio",
                        signatureModelId = "gemma3n-e4b",
                        backendPlans = GEMMA3N_E4B_BACKEND_PLANS
                    )
                )
            }
        }
    }

    private fun buildEngineSignature(modelTarget: ModelTarget): String {
        val backendSignature = modelTarget.backendPlans
            .ifEmpty { GEMMA3N_E4B_BACKEND_PLANS }
            .distinctBy { plan -> plan.key() }
            .joinToString(separator = ",") { plan -> plan.key() }
        return "${modelTarget.signatureModelId}|${modelTarget.uri}|$backendSignature"
    }

    private fun runTextConversationGeneration(
        activeEngine: Engine,
        prompt: String,
        onPartialResult: ((String) -> Unit)?
    ): GenerationAttempt {
        return runDirectAudioConversation(
            activeEngine = activeEngine,
            contents = listOf(Content.Text(prompt)),
            onPartialResult = onPartialResult,
            failureLabel = "LiteRT-LM text generation",
            preferStreamingFinalResult = true
        )
    }

    private fun runGeneration(
        activeEngine: Engine,
        request: TranslationRequest,
        directAudioBytes: ByteArray?,
        onPartialResult: ((String) -> Unit)?,
        onStatusUpdate: ((RuntimeStatusUpdate) -> Unit)?
    ): GenerationAttempt {
        onStatusUpdate?.invoke(
            RuntimeStatusUpdate(
                message = "LiteRT-LM generating... (backend=${activeBackendLabel()})",
                isModelLoading = false,
                modelLoadProgressPercent = null
            )
        )
        return if (directAudioBytes != null) {
            runDirectAudioGeneration(
                activeEngine = activeEngine,
                sourceLanguage = request.sourceLanguage.code,
                targetLanguage = request.targetLanguage.code,
                audioWavBytes = directAudioBytes,
                conversationContext = request.conversationContext,
                directAudioPromptSkipNoSpeech = request.directAudioPromptSkipNoSpeech,
                onPartialResult = onPartialResult
            )
        } else {
            val prompt = buildPrompt(
                sourceLanguage = request.sourceLanguage.code,
                targetLanguage = request.targetLanguage.code,
                transcript = request.transcript,
                conversationContext = request.conversationContext
            )
            runTextConversationGeneration(
                activeEngine = activeEngine,
                prompt = prompt,
                onPartialResult = onPartialResult
            )
        }
    }

    private fun runDirectAudioGeneration(
        activeEngine: Engine,
        sourceLanguage: String,
        targetLanguage: String,
        audioWavBytes: ByteArray,
        conversationContext: List<ConversationContextTurn>,
        directAudioPromptSkipNoSpeech: Boolean,
        onPartialResult: ((String) -> Unit)?
    ): GenerationAttempt {
        val prompt = buildAudioPrompt(
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            conversationContext = conversationContext,
            directAudioPromptSkipNoSpeech = directAudioPromptSkipNoSpeech
        )
        val contents = listOf(
            Content.Text(prompt),
            Content.AudioBytes(audioWavBytes)
        )
        val primary = runDirectAudioConversation(
            activeEngine = activeEngine,
            contents = contents,
            onPartialResult = onPartialResult,
            preferStreamingFinalResult = true
        )
        if (primary is GenerationAttempt.Success && primary.text.isNotBlank()) {
            return primary
        }
        val primaryReason = (primary as? GenerationAttempt.Failed)?.reason.orEmpty()
        if (!shouldRetryDirectAudioWithFile(primaryReason)) {
            return primary
        }

        val tempAudioFile = writeAudioTempFile(audioWavBytes)
            ?: return GenerationAttempt.Failed(
                if (primaryReason.isNotBlank()) {
                    "$primaryReason; also failed to stage audio file for fallback."
                } else {
                    "Failed to stage audio file for direct-audio fallback."
                }
            )
        try {
            val fileFallback = runDirectAudioConversation(
                activeEngine = activeEngine,
                contents = listOf(
                    Content.Text(prompt),
                    Content.AudioFile(tempAudioFile.absolutePath)
                ),
                onPartialResult = onPartialResult,
                preferStreamingFinalResult = true
            )
            if (fileFallback is GenerationAttempt.Success && fileFallback.text.isNotBlank()) {
                return fileFallback
            }
            val fallbackReason = (fileFallback as? GenerationAttempt.Failed)?.reason.orEmpty()
            return GenerationAttempt.Failed(
                listOf(primaryReason, fallbackReason)
                    .filter { it.isNotBlank() }
                    .joinToString("; ")
                    .ifBlank { "LiteRT-LM direct audio failed." }
            )
        } finally {
            runCatching { tempAudioFile.delete() }
        }
    }

    private fun runDirectAudioConversation(
        activeEngine: Engine,
        contents: List<Content>,
        onPartialResult: ((String) -> Unit)?,
        failureLabel: String = "LiteRT-LM audio generation",
        preferStreamingFinalResult: Boolean = false
    ): GenerationAttempt {
        val conversationConfig = buildConversationConfig()
        val conversation = activeEngine.createConversation(conversationConfig)
        registerActiveConversation(conversation)
        var streamFailure: String? = null
        var rawStream = ""
        var streamText = ""
        var streamCompletedSuccessfully = false
        try {
            val message = Message.Companion.of(contents)
            val latch = CountDownLatch(1)
            var callbackError: Throwable? = null
            var lastPartial = ""
            var streamStarted = false

            runCatching {
                conversation.sendMessageAsync(
                    message,
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val incoming = extractTextFromMessage(message)
                            if (incoming.isBlank()) return
                            rawStream = mergeStreamingChunk(rawStream, incoming)
                            val partial = cleanModelOutput(rawStream)
                            if (partial.isNotBlank() && partial != lastPartial) {
                                lastPartial = partial
                                streamText = partial
                                onPartialResult?.invoke(partial)
                            }
                        }

                        override fun onDone() {
                            latch.countDown()
                        }

                        override fun onError(throwable: Throwable) {
                            callbackError = throwable
                            latch.countDown()
                        }
                    }
                    )
                streamStarted = true
            }.getOrElse { error ->
                streamFailure =
                    "$failureLabel stream start failed: ${error.message ?: error.javaClass.simpleName}"
                Unit
            }

            if (streamStarted) {
                val completed = latch.await(GENERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!completed) {
                    runCatching { conversation.cancelProcess() }
                    streamFailure = "$failureLabel stream timed out (${GENERATION_TIMEOUT_SECONDS}s)."
                }

                callbackError?.let { error ->
                    streamFailure = "$failureLabel stream failed: ${error.message ?: error.javaClass.simpleName}"
                }
                if (completed && callbackError == null && streamFailure == null) {
                    streamCompletedSuccessfully = true
                }
            }
        } finally {
            clearActiveConversation(conversation)
            runCatching { conversation.close() }
        }
        if (streamText.isBlank()) {
            streamText = cleanModelOutput(rawStream)
        }
        if (preferStreamingFinalResult && streamCompletedSuccessfully && streamText.isNotBlank()) {
            return GenerationAttempt.Success(streamText)
        }

        val finalAttempt = runDirectAudioConversationSync(
            activeEngine = activeEngine,
            contents = contents,
            failureLabel = failureLabel
        )
        return when (finalAttempt) {
            is GenerationAttempt.Success -> {
                if (finalAttempt.text.isBlank()) {
                    if (streamText.isNotBlank()) {
                        Log.w(
                            logTag,
                            "$failureLabel final non-streaming was empty. " +
                                "Using streaming output fallback."
                        )
                        GenerationAttempt.Success(streamText)
                    } else {
                        val reasons = buildList {
                            add("$failureLabel final non-streaming result was empty.")
                            streamFailure?.let { add(it) }
                        }.joinToString("; ")
                        GenerationAttempt.Failed(reasons)
                    }
                } else {
                    finalAttempt
                }
            }
            is GenerationAttempt.Failed -> {
                val reasons = buildList {
                    add(finalAttempt.reason)
                    streamFailure?.let { add(it) }
                }.joinToString("; ")
                if (streamText.isNotBlank() && shouldUseStreamingFallback(reasons)) {
                    Log.w(
                        logTag,
                        "$failureLabel final non-streaming failed. " +
                            "Using streaming output fallback. reason=$reasons"
                    )
                    GenerationAttempt.Success(streamText)
                } else {
                    GenerationAttempt.Failed(reasons)
                }
            }
        }
    }

    private fun runDirectAudioConversationSync(
        activeEngine: Engine,
        contents: List<Content>,
        failureLabel: String
    ): GenerationAttempt {
        val conversationConfig = buildConversationConfig()
        val conversation = activeEngine.createConversation(conversationConfig)
        registerActiveConversation(conversation)
        try {
            val message = Message.Companion.of(contents)
            val result = runCatching {
                extractTextFromMessage(conversation.sendMessage(message))
            }.getOrElse { error ->
                return GenerationAttempt.Failed(
                    "$failureLabel final non-streaming failed: ${error.message ?: error.javaClass.simpleName}"
                )
            }
            return GenerationAttempt.Success(cleanModelOutput(result))
        } finally {
            clearActiveConversation(conversation)
            runCatching { conversation.close() }
        }
    }

    private fun buildConversationConfig(): ConversationConfig {
        return ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = DEFAULT_TOP_K,
                topP = DEFAULT_TOP_P,
                temperature = DEFAULT_TEMPERATURE,
                seed = DEFAULT_SEED
            )
        )
    }

    private fun shouldRetryDirectAudioWithFile(reason: String): Boolean {
        val normalized = reason.lowercase()
        return normalized.contains("must be preprocessed") ||
            normalized.contains("audio must be preprocessed") ||
            normalized.contains("sessionbasic")
    }

    private fun shouldUseStreamingFallback(reason: String): Boolean {
        val normalized = reason.lowercase()
        return normalized.contains("opencl") ||
            normalized.contains("final non-streaming failed") ||
            normalized.contains("nativesendmessage") ||
            normalized.contains("status code: 2")
    }

    private fun writeAudioTempFile(audioWavBytes: ByteArray): File? {
        val cacheDir = ensureAudioCacheDirectory() ?: return null
        val file = File(cacheDir, "direct-audio-${System.nanoTime()}.wav")
        return runCatching {
            file.outputStream().use { output ->
                output.write(audioWavBytes)
                output.flush()
            }
            file
        }.getOrNull()
    }

    private fun ensureAudioCacheDirectory(): File? {
        val base = appContext.getExternalFilesDir(null) ?: appContext.noBackupFilesDir
        val dir = File(base, AUDIO_CACHE_DIR_NAME)
        return if (dir.exists() || dir.mkdirs()) dir else null
    }

    private fun extractTextFromMessage(message: Message?): String {
        if (message == null) return ""
        return message.contents
            .asSequence()
            .mapNotNull { content -> (content as? Content.Text)?.text }
            .joinToString(separator = "")
    }

    private fun mergeStreamingChunk(
        existingRaw: String,
        incomingRaw: String?
    ): String {
        if (incomingRaw.isNullOrEmpty()) return existingRaw
        if (existingRaw.isEmpty()) return incomingRaw

        if (incomingRaw.startsWith(existingRaw)) {
            return incomingRaw
        }
        if (existingRaw.startsWith(incomingRaw) || existingRaw.endsWith(incomingRaw)) {
            return existingRaw
        }

        val overlap = longestSuffixPrefixOverlap(existingRaw, incomingRaw)
        return existingRaw + incomingRaw.substring(overlap)
    }

    private fun longestSuffixPrefixOverlap(
        left: String,
        right: String
    ): Int {
        val max = minOf(left.length, right.length)
        for (size in max downTo 1) {
            if (left.regionMatches(left.length - size, right, 0, size, ignoreCase = false)) {
                return size
            }
        }
        return 0
    }

    private fun ensureEngineLoaded(
        signature: String,
        modelUri: Uri,
        backendPlans: List<BackendPlan>,
        onStatusUpdate: ((RuntimeStatusUpdate) -> Unit)?
    ): RuntimeAttempt? {
        synchronized(ENGINE_LOCK) {
            val orderedPlans = backendPlans
                .ifEmpty { GEMMA3N_E4B_BACKEND_PLANS }
                .distinctBy { plan -> plan.key() }

            if (loadedSignature == signature && engine != null) {
                onStatusUpdate?.invoke(
                    RuntimeStatusUpdate(
                        message = "LiteRT-LM model ready (cached, backend=${activeBackendLabel()}).",
                        isModelLoading = false,
                        modelLoadProgressPercent = 100
                    )
                )
                return null
            }

            closeEngineLocked()
            onStatusUpdate?.invoke(
                RuntimeStatusUpdate(
                    message = "LiteRT-LM model loading...",
                    isModelLoading = true,
                    modelLoadProgressPercent = 10
                )
            )

            val prepared = prepareModelPath(
                modelUri = modelUri,
                onProgress = { message, progress ->
                    onStatusUpdate?.invoke(
                        RuntimeStatusUpdate(
                            message = message,
                            isModelLoading = true,
                            modelLoadProgressPercent = progress
                        )
                    )
                }
            )
            val modelPath = when (prepared) {
                is ModelPathPreparation.Ready -> prepared.path
                is ModelPathPreparation.Error -> return RuntimeAttempt.Failed(prepared.reason)
            }

            val engineCacheDir = ensureEngineCacheDirectory()
                ?: return RuntimeAttempt.Failed("Failed to prepare LiteRT-LM engine cache directory.")

            val errors = mutableListOf<String>()
            for (plan in orderedPlans) {
                onStatusUpdate?.invoke(
                    RuntimeStatusUpdate(
                        message = "Initializing LiteRT-LM engine (backend=${plan.label()})...",
                        isModelLoading = true,
                        modelLoadProgressPercent = 85
                    )
                )
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = plan.backend,
                    visionBackend = plan.visionBackend,
                    audioBackend = plan.audioBackend,
                    maxNumTokens = MAX_TOKENS,
                    cacheDir = engineCacheDir.absolutePath
                )
                val candidate = runCatching { Engine(config) }.getOrElse { error ->
                    errors += "backend=${plan.label()} engine create failed: ${error.message ?: error.javaClass.simpleName}"
                    continue
                }

                val initialized = runCatching { candidate.initialize() }
                if (initialized.isSuccess) {
                    engine = candidate
                    loadedSignature = signature
                    loadedBackend = plan.backend
                    loadedBackendPlan = plan
                    Log.i(logTag, "LiteRT-LM model loaded with backend=${plan.label()} modelPath=$modelPath")
                    onStatusUpdate?.invoke(
                        RuntimeStatusUpdate(
                            message = "LiteRT-LM model loaded. backend=${plan.label()}",
                            isModelLoading = false,
                            modelLoadProgressPercent = 100
                        )
                    )
                    return null
                }

                val detail = initialized.exceptionOrNull()?.message
                    ?: initialized.exceptionOrNull()?.javaClass?.simpleName
                    ?: "unknown"
                errors += "backend=${plan.label()} init failed: $detail"
                runCatching { candidate.close() }
                Log.w(logTag, "LiteRT-LM backend init failed backend=${plan.label()}: $detail")
            }

            val mergedErrors = errors.joinToString("; ")
            return RuntimeAttempt.Failed(
                "LiteRT-LM engine initialization failed. " +
                    "Tried backends: ${orderedPlans.joinToString { it.label() }}. " +
                    "Details: $mergedErrors. " +
                    "Ensure LiteRT-LM GPU backend prerequisites are available on this device."
            )
        }
    }

    private sealed interface ModelPathPreparation {
        data class Ready(val path: String) : ModelPathPreparation
        data class Error(val reason: String) : ModelPathPreparation
    }

    private data class ModelTarget(
        val uri: Uri,
        val runtimeModelId: String,
        val signatureModelId: String,
        val backendPlans: List<BackendPlan>
    )

    private sealed interface ModelTargetResolution {
        data class Ready(val target: ModelTarget) : ModelTargetResolution
        data class NotAvailable(val reason: String) : ModelTargetResolution
    }

    private data class BackendPlan(
        val backend: Backend,
        val visionBackend: Backend,
        val audioBackend: Backend
    ) {
        fun key(): String = "${backend.name}|${visionBackend.name}|${audioBackend.name}"

        fun label(): String {
            return "${backend.name}/vision=${visionBackend.name}/audio=${audioBackend.name}"
        }
    }

    private fun prepareModelPath(
        modelUri: Uri,
        onProgress: ((String, Int) -> Unit)? = null
    ): ModelPathPreparation {
        if (modelUri.scheme == "file") {
            val path = modelUri.path?.takeIf { it.isNotBlank() }
                ?: return ModelPathPreparation.Error("Invalid file model path.")
            val file = File(path)
            validateModelFile(file)?.let { reason ->
                return ModelPathPreparation.Error(reason)
            }
            return ModelPathPreparation.Ready(path)
        }
        if (modelUri.scheme != "content") {
            return ModelPathPreparation.Error("Unsupported model URI scheme: ${modelUri.scheme ?: "null"}")
        }

        val metadata = readSourceMetadata(modelUri)
            ?: return ModelPathPreparation.Error("Failed to resolve model metadata.")
        val extension = metadata.displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: MODEL_EXTENSION
        if (extension != MODEL_EXTENSION) {
            return ModelPathPreparation.Error(
                "LiteRT-LM mode requires .$MODEL_EXTENSION model file. Found .$extension."
            )
        }

        val cacheDir = ensureModelCacheDirectory()
            ?: return ModelPathPreparation.Error("Failed to prepare LiteRT-LM model cache directory.")
        onProgress?.invoke("Preparing LiteRT-LM model cache...", 20)
        val cacheName = buildCacheName(modelUri, metadata)
        val targetFile = File(cacheDir, cacheName)
        if (targetFile.exists()) {
            val cacheLooksValid = (metadata.sizeBytes <= 0L || targetFile.length() == metadata.sizeBytes) &&
                validateModelFile(targetFile) == null
            if (cacheLooksValid) {
                onProgress?.invoke("Using cached LiteRT-LM model.", 70)
                return ModelPathPreparation.Ready(targetFile.absolutePath)
            }
            runCatching { targetFile.delete() }
        }

        val tempFile = File(cacheDir, "$cacheName.part")
        runCatching { tempFile.delete() }
        onProgress?.invoke("Copying LiteRT-LM model into app cache...", 25)

        val copied = runCatching {
            appContext.contentResolver.openInputStream(modelUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var totalBytes = 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        totalBytes += read
                        if (metadata.sizeBytes > 0L) {
                            val progress = 25 + ((totalBytes * 45L) / metadata.sizeBytes)
                                .toInt()
                                .coerceIn(0, 45)
                            if (progress != lastReported) {
                                lastReported = progress
                                onProgress?.invoke("Copying LiteRT-LM model into app cache...", progress)
                            }
                        }
                    }
                    output.flush()
                }
            } != null
        }.getOrElse { error ->
            Log.w(logTag, "LiteRT-LM model copy failed uri=$modelUri: ${error.message ?: error.javaClass.simpleName}")
            false
        }
        if (!copied || !tempFile.exists()) {
            runCatching { tempFile.delete() }
            return ModelPathPreparation.Error("Failed to copy LiteRT-LM model from storage URI.")
        }

        if (metadata.sizeBytes > 0L && tempFile.length() != metadata.sizeBytes) {
            runCatching { tempFile.delete() }
            return ModelPathPreparation.Error("Copied LiteRT-LM model size mismatch.")
        }
        // Temp copy file uses ".part" suffix until finalize, so skip extension check here.
        validateModelFile(tempFile, enforceExtension = false)?.let { reason ->
            runCatching { tempFile.delete() }
            return ModelPathPreparation.Error(reason)
        }

        runCatching { targetFile.delete() }
        if (!tempFile.renameTo(targetFile)) {
            runCatching { tempFile.delete() }
            return ModelPathPreparation.Error("Failed to finalize cached LiteRT-LM model file.")
        }
        onProgress?.invoke("LiteRT-LM model cache ready.", 70)
        return ModelPathPreparation.Ready(targetFile.absolutePath)
    }

    private fun validateModelFile(
        file: File,
        enforceExtension: Boolean = true
    ): String? {
        if (!file.exists()) return "Model file not found: ${file.absolutePath}"
        if (!file.canRead()) return "Model file is not readable: ${file.absolutePath}"
        if (enforceExtension) {
            val extension = file.extension.lowercase()
            if (extension != MODEL_EXTENSION) {
                return "LiteRT-LM mode requires .$MODEL_EXTENSION model file. Found .$extension."
            }
        }
        if (file.length() <= 0L) {
            return "Model file is empty: ${file.absolutePath}"
        }
        return null
    }

    private data class SourceMetadata(
        val displayName: String?,
        val sizeBytes: Long,
        val lastModifiedMs: Long
    )

    private fun readSourceMetadata(modelUri: Uri): SourceMetadata? {
        var displayName: String? = null
        var sizeBytes = -1L
        var lastModifiedMs = -1L

        runCatching {
            appContext.contentResolver.query(modelUri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndex("last_modified")
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                    if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                        lastModifiedMs = cursor.getLong(modifiedIndex)
                    }
                }
            }
        }

        val fallbackDoc = DocumentFile.fromSingleUri(appContext, modelUri)
        if (displayName.isNullOrBlank()) {
            displayName = fallbackDoc?.name
        }
        if (sizeBytes <= 0L) {
            sizeBytes = fallbackDoc?.length() ?: -1L
        }
        if (lastModifiedMs <= 0L) {
            lastModifiedMs = fallbackDoc?.lastModified() ?: -1L
        }

        if (displayName.isNullOrBlank()) {
            displayName = "model.$MODEL_EXTENSION"
        }
        return SourceMetadata(
            displayName = displayName,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs
        )
    }

    private fun ensureModelCacheDirectory(): File? {
        val base = appContext.getExternalFilesDir(null) ?: appContext.noBackupFilesDir
        val dir = File(base, MODEL_CACHE_DIR_NAME)
        return if (dir.exists() || dir.mkdirs()) dir else null
    }

    private fun ensureEngineCacheDirectory(): File? {
        val base = appContext.getExternalFilesDir(null) ?: appContext.noBackupFilesDir
        val dir = File(base, ENGINE_CACHE_DIR_NAME)
        return if (dir.exists() || dir.mkdirs()) dir else null
    }

    private fun buildCacheName(
        modelUri: Uri,
        metadata: SourceMetadata
    ): String {
        val keySource = "${modelUri}|${metadata.sizeBytes}|${metadata.lastModifiedMs}"
        val digest = sha256Hex(keySource)
        return "$digest.$MODEL_EXTENSION"
    }

    private fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            builder.append(String.format("%02x", byte))
        }
        return builder.toString()
    }

    private fun buildPrompt(
        sourceLanguage: String,
        targetLanguage: String,
        transcript: String,
        conversationContext: List<ConversationContextTurn>
    ): String {
        val sourceCode = languageCode(sourceLanguage)
        val targetCode = languageCode(targetLanguage)
        val sourceName = languageName(sourceCode)
        val targetName = languageName(targetCode)
        val text = transcript.trim()
        return buildString {
            append("Task: Translate SOURCE from ")
            append(sourceName)
            append(" (")
            append(sourceCode)
            append(") to ")
            append(targetName)
            append(" (")
            append(targetCode)
            append(").\n")
            append("Output rules:\n")
            append("1. Return only the final ")
            append(targetName)
            append(" translation text.\n")
            append("2. Do not add explanations, notes, alternatives, romanization, or quotes.\n")
            append("3. Do not repeat, paraphrase, or continue generating after the translation.\n")
            append("4. Stop immediately after the translation.\n\n")
            append(buildConversationContextBlock(conversationContext))
            append("SOURCE:\n")
            append(text)
        }
    }

    private fun buildAudioPrompt(
        sourceLanguage: String,
        targetLanguage: String,
        conversationContext: List<ConversationContextTurn>,
        directAudioPromptSkipNoSpeech: Boolean
    ): String {
        val sourceCode = languageCode(sourceLanguage)
        val targetCode = languageCode(targetLanguage)
        val sourceName = languageName(sourceCode)
        val targetName = languageName(targetCode)
        return buildString {
            append("Task: Listen to the spoken ")
            append(sourceName)
            append(" audio input and translate it into ")
            append(targetName)
            append(" (")
            append(targetCode)
            append(").\n")
            append("Output format (strict):\n")
            append("SOURCE: [recognized ")
            append(sourceName)
            append(" text]\n")
            append("TRANSLATION: [final ")
            append(targetName)
            append(" translation]\n")
            append("Output rules:\n")
            append("1. Keep SOURCE in ")
            append(sourceName)
            append(" and TRANSLATION in ")
            append(targetName)
            append(".\n")
            append("2. Do not add explanations, notes, alternatives, romanization, or quotes.\n")
            append("3. Output exactly two lines beginning with SOURCE: and TRANSLATION:.\n")
            append("4. Do not output XML/HTML tags.\n")
            if (directAudioPromptSkipNoSpeech) {
                append("5. If audio is silent, unclear, or contains no intelligible speech, do NOT translate.\n")
                append("6. In that case, output EXACTLY these two lines and stop:\n")
                append("SOURCE: \n")
                append("TRANSLATION: [")
                append(DIRECT_AUDIO_NO_SPEECH_MARKER)
                append("]\n")
                append("7. Do not output any text before or after these two lines in no-speech case.\n")
                append("8. Stop immediately after the TRANSLATION line.\n")
            } else {
                append("5. Stop immediately after the TRANSLATION line.\n")
            }
            append(buildConversationContextBlock(conversationContext))
        }
    }

    private fun buildConversationContextBlock(
        conversationContext: List<ConversationContextTurn>
    ): String {
        if (conversationContext.isEmpty()) {
            return ""
        }
        return buildString {
            append("Conversation context (latest first, for disambiguation only):\n")
            conversationContext.forEachIndexed { index, turn ->
                append(index + 1)
                append(". ")
                append(turn.sourceLanguage.displayName)
                append(" -> ")
                append(turn.targetLanguage.displayName)
                append("\n")
                append("   SOURCE: ")
                append(turn.sourceText.trim())
                append("\n")
                append("   TRANSLATION: ")
                append(turn.translatedText.trim())
                append("\n")
            }
            append("Now process the current input below.\n\n")
        }
    }

    private fun parseDirectAudioOutput(raw: String): DirectAudioOutput {
        val cleaned = cleanModelOutput(raw)
        if (cleaned.isBlank()) {
            return DirectAudioOutput(
                recognizedText = "",
                translatedText = ""
            )
        }

        val sourceLine = extractLabeledValue(
            text = cleaned,
            label = "SOURCE"
        )
        val translationLine = extractLabeledValue(
            text = cleaned,
            label = "TRANSLATION"
        )
        if (sourceLine.isNotBlank() && translationLine.isNotBlank()) {
            return DirectAudioOutput(
                recognizedText = sanitizeStructuredField(sourceLine),
                translatedText = sanitizeStructuredField(translationLine)
            )
        }

        val sourceTag = extractTaggedValue(
            text = cleaned,
            tag = "source"
        )
        val translationTag = extractTaggedValue(
            text = cleaned,
            tag = "translation"
        )
        if (sourceTag.isNotBlank() && translationTag.isNotBlank()) {
            return DirectAudioOutput(
                recognizedText = sanitizeStructuredField(sourceTag),
                translatedText = sanitizeStructuredField(translationTag)
            )
        }

        return DirectAudioOutput(
            recognizedText = "",
            translatedText = sanitizeStructuredField(cleaned.trim())
        )
    }

    private fun isNoSpeechMarker(value: String): Boolean {
        val normalized = value.trim().uppercase()
            .replace("[", "")
            .replace("]", "")
            .replace("<", "")
            .replace(">", "")
        return normalized == DIRECT_AUDIO_NO_SPEECH_MARKER
    }

    private fun extractTaggedValue(
        text: String,
        tag: String
    ): String {
        val openCandidates = listOf(
            "<$tag>",
            "<$tag >",
            "$tag>"
        )
        val closeCandidates = listOf(
            "</$tag>",
            "< /$tag>",
            "< / $tag>",
            "/$tag>",
            "</ $tag>"
        )

        val lower = text.lowercase()
        var openIndex = -1
        var openLength = 0
        for (candidate in openCandidates) {
            val idx = lower.indexOf(candidate.lowercase())
            if (idx >= 0 && (openIndex < 0 || idx < openIndex)) {
                openIndex = idx
                openLength = candidate.length
            }
        }
        if (openIndex < 0) return ""

        val contentStart = openIndex + openLength
        var closeIndex = -1
        for (candidate in closeCandidates) {
            val idx = lower.indexOf(candidate.lowercase(), contentStart)
            if (idx >= 0 && (closeIndex < 0 || idx < closeIndex)) {
                closeIndex = idx
            }
        }
        if (closeIndex < 0) {
            val tail = text.substring(contentStart)
            val fallbackStopMarkers = when (tag.lowercase()) {
                "source" -> listOf(
                    "<translation>",
                    "translation>",
                    "translation:",
                    "< translation>",
                    "< /translation>"
                )
                "translation" -> listOf(
                    "<source>",
                    "source>",
                    "source:",
                    "< source>",
                    "< /source>"
                )
                else -> emptyList()
            }
            val fallbackStop = firstMarkerIndex(
                text = tail,
                markers = fallbackStopMarkers
            )
            return if (fallbackStop >= 0) {
                tail.substring(0, fallbackStop).trim()
            } else {
                tail.trim()
            }
        }
        return text.substring(contentStart, closeIndex).trim()
    }

    private fun extractLabeledValue(
        text: String,
        label: String
    ): String {
        val lines = text.lines()
        val prefix = "$label:"
        val otherPrefix = if (label.equals("SOURCE", ignoreCase = true)) {
            "TRANSLATION:"
        } else {
            "SOURCE:"
        }
        for ((index, line) in lines.withIndex()) {
            val raw = line.trim()
            val rawLower = raw.lowercase()
            val prefixIndex = rawLower.indexOf(prefix.lowercase())
            if (prefixIndex < 0) continue
            val headRaw = raw.substring(prefixIndex + prefix.length).trim()
            val head = cutBeforeLabel(headRaw, otherPrefix)
            if (head.isNotBlank()) return head
            for (next in (index + 1) until lines.size) {
                val candidate = lines[next].trim()
                if (candidate.isBlank()) continue
                if (candidate.contains("SOURCE:", ignoreCase = true) ||
                    candidate.contains("TRANSLATION:", ignoreCase = true)
                ) {
                    break
                }
                return candidate
            }
            return ""
        }
        return ""
    }

    private fun cutBeforeLabel(
        text: String,
        label: String
    ): String {
        if (text.isBlank()) return text
        val idx = text.indexOf(label, ignoreCase = true)
        if (idx < 0) return text
        return text.substring(0, idx).trim()
    }

    private fun firstMarkerIndex(
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

    private fun sanitizeStructuredField(value: String): String {
        if (value.isBlank()) return ""
        val sanitized = value
            .replace(Regex("(?i)<\\s*/?\\s*(source|translation)\\b[^>]*>"), " ")
            .replace(Regex("(?i)</?\\s*(source|translation)\\b"), " ")
            .replace(Regex("(?i)\\b(source|translation)\\s*:\\s*"), " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (isNoSpeechMarker(sanitized)) {
            DIRECT_AUDIO_NO_SPEECH_MARKER
        } else {
            sanitized
        }
    }

    private fun languageCode(code: String): String {
        return when (code.lowercase()) {
            "ja", "jp", "ja-jp" -> "ja"
            "en", "en-us", "en-gb" -> "en"
            "zh", "zh-cn", "zh-hans" -> "zh"
            else -> code.lowercase()
        }
    }

    private fun languageName(code: String): String {
        return when (code.lowercase()) {
            "ja" -> "Japanese"
            "en" -> "English"
            "zh" -> "Chinese"
            else -> code
        }
    }

    private fun cleanModelOutput(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text
            .replace("<bos>", "")
            .replace("<eos>", "")
            .replace("<start_of_turn>", "")
            .replace("<end_of_turn>", "")
            .replace("model\n", "")
            .replace("assistant\n", "")
            .trim()
    }

    private fun closeEngineLocked() {
        runCatching { engine?.close() }
        engine = null
        loadedSignature = null
        loadedBackend = Backend.GPU
        loadedBackendPlan = null
        activeConversation = null
    }

    private fun registerActiveConversation(conversation: Conversation) {
        synchronized(ENGINE_LOCK) {
            activeConversation = conversation
        }
    }

    private fun clearActiveConversation(conversation: Conversation) {
        synchronized(ENGINE_LOCK) {
            if (activeConversation === conversation) {
                activeConversation = null
            }
        }
    }

    private fun activeBackendLabel(): String {
        return loadedBackendPlan?.label() ?: loadedBackend.name
    }

    private companion object {
        private const val MODEL_EXTENSION = "litertlm"
        private const val MAX_TOKENS = 512
        private const val DEFAULT_TOP_K = 1
        private const val DEFAULT_TOP_P = 1.0
        private const val DEFAULT_TEMPERATURE = 0.0
        private const val DEFAULT_SEED = 1
        private const val GENERATION_TIMEOUT_SECONDS = 180L
        private const val COPY_BUFFER_SIZE = 8 * 1024 * 1024
        private const val MODEL_CACHE_DIR_NAME = "litertlm-model-cache"
        private const val ENGINE_CACHE_DIR_NAME = "litertlm-engine-cache"
        private const val AUDIO_CACHE_DIR_NAME = "litertlm-audio-cache"
        private const val DIRECT_AUDIO_NO_SPEECH_MARKER = "NO_SPEECH"
        private val ENGINE_LOCK = Any()
        private val GEMMA3N_E4B_BACKEND_PLANS = listOf(
            BackendPlan(
                backend = Backend.GPU,
                visionBackend = Backend.GPU,
                audioBackend = Backend.CPU
            )
        )

        @Volatile
        private var loadedSignature: String? = null

        @Volatile
        private var loadedBackend: Backend = Backend.GPU

        @Volatile
        private var loadedBackendPlan: BackendPlan? = null

        @Volatile
        private var engine: Engine? = null

        @Volatile
        private var activeConversation: Conversation? = null
    }

    private sealed interface GenerationAttempt {
        data class Success(val text: String) : GenerationAttempt
        data class Failed(val reason: String) : GenerationAttempt
    }

    private data class DirectAudioOutput(
        val recognizedText: String,
        val translatedText: String
    )
}
