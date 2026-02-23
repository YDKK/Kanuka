package YDKK.kanuka.engine

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ModelSlot(val displayName: String) {
    GEMMA_3N_E4B_LITERTLM("Gemma 3n E4B LiteRT-LM model")
}

sealed interface ModelResolutionResult {
    data class Ready(
        val rootUri: Uri,
        val models: ResolvedModels
    ) : ModelResolutionResult

    data class Error(
        val message: String
    ) : ModelResolutionResult
}

data class ResolvedModels(
    val gemma3nE4bLiteRtLmModelUri: Uri?
) {
    fun uriFor(slot: ModelSlot): Uri? {
        return when (slot) {
            ModelSlot.GEMMA_3N_E4B_LITERTLM -> gemma3nE4bLiteRtLmModelUri
        }
    }
}

class ModelResolver(
    private val context: Context
) {
    suspend fun resolve(modelFileUri: String?): ModelResolutionResult = withContext(Dispatchers.IO) {
        if (modelFileUri.isNullOrBlank()) {
            return@withContext ModelResolutionResult.Error("Model file is not configured.")
        }

        val selectedUri = runCatching { modelFileUri.toUri() }.getOrNull()
            ?: return@withContext ModelResolutionResult.Error("Model file URI is invalid.")

        val selectedFile = DocumentFile.fromSingleUri(context, selectedUri)
            ?: return@withContext ModelResolutionResult.Error("Cannot access model file.")

        if (!selectedFile.isFile) {
            return@withContext ModelResolutionResult.Error("Selected URI is not a file.")
        }

        if (!selectedFile.name.equals(EXPECTED_MODEL_FILE_NAME, ignoreCase = true)) {
            return@withContext ModelResolutionResult.Error(
                "Select $EXPECTED_MODEL_FILE_NAME as model file."
            )
        }

        return@withContext ModelResolutionResult.Ready(
            rootUri = selectedUri,
            models = ResolvedModels(
                gemma3nE4bLiteRtLmModelUri = selectedUri
            )
        )
    }

    private companion object {
        private const val EXPECTED_MODEL_FILE_NAME = "gemma-3n-E4B-it-int4.litertlm"
    }
}
