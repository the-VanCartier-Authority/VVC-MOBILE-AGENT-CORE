package com.vancartier.vvcmobileagentcore.edge

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import com.vancartier.vvcmobileagentcore.notification.VvcNotificationScheduler
import com.vancartier.vvcmobileagentcore.security.VvcHashCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class VvcEdgeModelManager(
    context: Context,
    private val notificationScheduler: VvcNotificationScheduler = VvcNotificationScheduler(context),
    private val modelDirectory: String = MODEL_DIRECTORY
) {
    private val applicationContext = context.applicationContext
    private val assetManager: AssetManager = applicationContext.assets
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val interpreters = ConcurrentHashMap<String, Interpreter>()
    private val modelNames = ConcurrentHashMap<String, String>()
    private val verifiedHashes = ConcurrentHashMap<String, String>()
    private val rejectedModels = ConcurrentHashMap<String, String>()
    private val modelLoadingMutex = Mutex()
    private val availableModelPaths = ConcurrentHashMap<String, String>() // normalized -> asset path
    private val isInitialized = ConcurrentHashMap<String, Boolean>() // Track which models are loaded

    val initialization: Deferred<Unit> = scope.async {
        scanAndCatalogModels()
    }

    suspend fun awaitReady() {
        initialization.await()
    }

    fun processAudioScribe(audioFile: File): String {
        if (!initialization.isCompleted) {
            return "AUDIO_SCRIBE_INIT_PENDING"
        }
        if (!audioFile.exists() || !audioFile.isFile) {
            return "AUDIO_SCRIBE_INPUT_NOT_FOUND"
        }

        val modelKey = findAvailableModelKey(AUDIO_MODEL_MARKERS) ?: return "AUDIO_SCRIBE_MODEL_NOT_AVAILABLE"
        val interpreter = interpreters[modelKey] ?: run {
            loadModelSync(modelKey)
            interpreters[modelKey]
        } ?: return "AUDIO_SCRIBE_INTERPRETER_NOT_AVAILABLE"
        
        val signal = audioFile.readBytes().toFloatVector(MAX_AUDIO_SAMPLES)
        val output = runSingleInputFloatInference(interpreter, signal)
        return formatTopResult("AUDIO_SCRIBE_OFFLINE", output, "Audio Scribe")
    }

    fun processAskImage(imageBitmap: Bitmap): String {
        if (!initialization.isCompleted) {
            return "ASK_IMAGE_INIT_PENDING"
        }

        val modelKey = findAvailableModelKey(IMAGE_MODEL_MARKERS) ?: return "ASK_IMAGE_MODEL_NOT_AVAILABLE"
        val interpreter = interpreters[modelKey] ?: run {
            loadModelSync(modelKey)
            interpreters[modelKey]
        } ?: return "ASK_IMAGE_INTERPRETER_NOT_AVAILABLE"
        
        val pixels = imageBitmap.toNormalizedFloatVector(MAX_IMAGE_VALUES)
        val output = runSingleInputFloatInference(interpreter, pixels)
        return formatTopResult("ASK_IMAGE_OFFLINE", output, "Ask Image")
    }

    fun processMobileActions(patternData: String): Int {
        if (!initialization.isCompleted) {
            return ACTION_INIT_PENDING
        }

        val modelKey = findAvailableModelKey(ACTION_MODEL_MARKERS) ?: return ACTION_MODEL_NOT_AVAILABLE
        val interpreter = interpreters[modelKey] ?: run {
            loadModelSync(modelKey)
            interpreters[modelKey]
        } ?: return ACTION_INTERPRETER_NOT_AVAILABLE
        
        val features = patternData.encodeToByteArray().toFloatVector(MAX_PATTERN_VALUES)
        val output = runSingleInputFloatInference(interpreter, features)
        val topIndex = output.indices.maxByOrNull { output[it] } ?: ACTION_EMPTY_RESULT
        if (topIndex >= 0 && output.getOrElse(topIndex) { 0.0f } < ANOMALY_CONFIDENCE_THRESHOLD) {
            notificationScheduler.scheduleModelAnomalyAlert(
                title = "VVC Mobile Actions: anomalía",
                message = "Clasificación local con baja confianza: ${"%.5f".format(Locale.US, output[topIndex])}."
            )
        }
        return topIndex
    }

    fun loadedModelReport(): String {
        if (!initialization.isCompleted) {
            return "MODEL_SCAN_PENDING"
        }
        val loadedReport = if (modelNames.isEmpty()) {
            "MODEL_SCAN_COMPLETE: 0 modelos verificados en assets/models"
        } else {
            modelNames.values.sorted().joinToString(
                separator = "\n",
                prefix = "MODEL_SCAN_COMPLETE:\n"
            ) { assetPath -> "- $assetPath · SHA-256 ${verifiedHashes[assetPath].orEmpty().take(HASH_PREVIEW_LENGTH)}" }
        }
        if (rejectedModels.isEmpty()) {
            return loadedReport
        }
        val rejectedReport = rejectedModels.entries.sortedBy { entry -> entry.key }.joinToString(
            separator = "\n",
            prefix = "\nMODEL_REJECTED:\n"
        ) { entry -> "- ${entry.key}: ${entry.value}" }
        return loadedReport + rejectedReport
    }

    fun close() {
        interpreters.values.forEach { interpreter -> interpreter.close() }
        interpreters.clear()
        modelNames.clear()
        verifiedHashes.clear()
        rejectedModels.clear()
        availableModelPaths.clear()
        isInitialized.clear()
        scope.cancel()
    }

    /**
     * Scan and catalog all available models without loading them into memory.
     * This is fast and memory-efficient.
     */
    private fun scanAndCatalogModels() {
        val paths = listModelAssetPaths(modelDirectory)
        paths.forEach { assetPath ->
            val normalizedName = assetPath.lowercase(Locale.US)
            if (normalizedName.endsWith(".tflite") || normalizedName.endsWith(".lite") || normalizedName.endsWith(".litert")) {
                // Verify but don't load yet
                verifyModelIntegrity(assetPath, normalizedName)
            }
        }
    }

    /**
     * Verify model integrity without loading it into memory.
     */
    private fun verifyModelIntegrity(assetPath: String, normalizedName: String) {
        val expectedHash = VvcHashCalculator.readAssetSha256Sidecar(assetManager, "$assetPath.sha256")
        if (expectedHash.isNullOrBlank()) {
            rejectedModels[assetPath] = "SHA-256 lateral ausente"
            notificationScheduler.scheduleModelAnomalyAlert(
                title = "Integridad VVC bloqueada",
                message = "El modelo $assetPath no se cargó porque no existe $assetPath.sha256."
            )
            return
        }

        val actualHash = VvcHashCalculator.calculateAssetSha256(assetManager, assetPath)
        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
            rejectedModels[assetPath] = "SHA-256 inválido"
            notificationScheduler.scheduleModelAnomalyAlert(
                title = "Integridad VVC bloqueada",
                message = "Hash inválido en $assetPath. Esperado $expectedHash, calculado $actualHash."
            )
            return
        }

        // Model is verified, catalog it for lazy loading
        availableModelPaths[normalizedName] = assetPath
        modelNames[normalizedName] = assetPath
        verifiedHashes[assetPath] = actualHash
    }

    /**
     * Load a model into memory synchronously (blocking).
     * Used from non-suspend contexts (processAudioScribe, processAskImage, etc.)
     */
    private fun loadModelSync(modelKey: String) {
        // Fast path: already loaded
        if (interpreters.containsKey(modelKey)) {
            return
        }

        val assetPath = availableModelPaths[modelKey] ?: return
        loadModelIntoMemory(modelKey, assetPath)
    }

    /**
     * Load a single model into memory.
     */
    private fun loadModelIntoMemory(normalizedName: String, assetPath: String) {
        try {
            val modelBuffer = loadMappedAsset(assetPath)
            val options = Interpreter.Options().apply {
                setNumThreads(DEFAULT_THREAD_COUNT)
            }
            interpreters[normalizedName] = Interpreter(modelBuffer, options)
            isInitialized[normalizedName] = true
        } catch (e: Exception) {
            rejectedModels[assetPath] = "Error al cargar: ${e.message}"
            notificationScheduler.scheduleModelAnomalyAlert(
                title = "Error al cargar modelo VVC",
                message = "No se pudo cargar $assetPath: ${e.message}"
            )
        }
    }

    private fun listModelAssetPaths(directory: String): List<String> {
        val directChildren = assetManager.list(directory).orEmpty()
        return directChildren.flatMap { child ->
            val childPath = "$directory/$child"
            val nestedChildren = assetManager.list(childPath).orEmpty()
            if (nestedChildren.isEmpty()) {
                listOf(childPath)
            } else {
                listModelAssetPaths(childPath)
            }
        }
    }

    private fun loadMappedAsset(assetPath: String): MappedByteBuffer {
        assetManager.openFd(assetPath).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { inputStream ->
                val channel = inputStream.channel
                return channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }
        }
    }

    private fun findAvailableModelKey(markers: Array<String>): String? {
        return availableModelPaths.keys.firstOrNull { key -> markers.any { marker -> key.contains(marker) } }
    }

    private fun runSingleInputFloatInference(interpreter: Interpreter, sourceValues: FloatArray): FloatArray {
        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)
        val inputBuffer = sourceValues.toTensorBuffer(inputTensor)
        val outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()
        return outputBuffer.toFloatArray(outputTensor)
    }

    private fun FloatArray.toTensorBuffer(tensor: Tensor): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
        val valueCount = tensor.numBytes() / tensor.dataType().byteSize()
        for (index in 0 until valueCount) {
            val value = this.getOrElse(index) { 0.0f }
            when (tensor.dataType()) {
                DataType.FLOAT32 -> buffer.putFloat(value)
                DataType.INT32 -> buffer.putInt(value.toInt())
                DataType.UINT8 -> buffer.put(value.coerceIn(0.0f, 255.0f).toInt().toByte())
                DataType.INT64 -> buffer.putLong(value.toLong())
                DataType.BOOL -> buffer.put((if (value > 0.0f) 1 else 0).toByte())
                DataType.INT16 -> buffer.putShort(value.toInt().toShort())
                DataType.INT8 -> buffer.put(value.coerceIn(-128.0f, 127.0f).toInt().toByte())
                else -> buffer.putFloat(value)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun ByteBuffer.toFloatArray(tensor: Tensor): FloatArray {
        val valueCount = max(1, tensor.numBytes() / tensor.dataType().byteSize())
        val values = FloatArray(valueCount)
        for (index in 0 until valueCount) {
            values[index] = when (tensor.dataType()) {
                DataType.FLOAT32 -> getFloat()
                DataType.INT32 -> getInt().toFloat()
                DataType.UINT8 -> (get().toInt() and UNSIGNED_BYTE_MASK).toFloat()
                DataType.INT64 -> getLong().toFloat()
                DataType.BOOL -> if (get().toInt() != 0) 1.0f else 0.0f
                DataType.INT16 -> getShort().toFloat()
                DataType.INT8 -> get().toFloat()
                else -> getFloat()
            }
        }
        return values
    }

    private fun ByteArray.toFloatVector(maxValues: Int): FloatArray {
        val safeSize = min(size, maxValues)
        return FloatArray(safeSize) { index ->
            (this[index].toInt() and UNSIGNED_BYTE_MASK) / UNSIGNED_BYTE_NORMALIZER
        }
    }

    private fun Bitmap.toNormalizedFloatVector(maxValues: Int): FloatArray {
        val widthStep = max(1, width / IMAGE_SAMPLE_WIDTH)
        val heightStep = max(1, height / IMAGE_SAMPLE_HEIGHT)
        val values = ArrayList<Float>(maxValues)
        var y = 0
        while (y < height && values.size < maxValues) {
            var x = 0
            while (x < width && values.size < maxValues) {
                val pixel = getPixel(x, y)
                values.add(((pixel shr 16) and UNSIGNED_BYTE_MASK) / UNSIGNED_BYTE_NORMALIZER)
                if (values.size < maxValues) {
                    values.add(((pixel shr 8) and UNSIGNED_BYTE_MASK) / UNSIGNED_BYTE_NORMALIZER)
                }
                if (values.size < maxValues) {
                    values.add((pixel and UNSIGNED_BYTE_MASK) / UNSIGNED_BYTE_NORMALIZER)
                }
                x += widthStep
            }
            y += heightStep
        }
        return values.toFloatArray()
    }

    private fun formatTopResult(prefix: String, output: FloatArray, skillName: String): String {
        if (output.isEmpty()) {
            notificationScheduler.scheduleModelAnomalyAlert(
                title = "VVC $skillName: salida vacía",
                message = "El modelo local devolvió un tensor de salida vacío."
            )
            return "${prefix}:EMPTY_RESULT"
        }
        val topIndex = output.indices.maxByOrNull { output[it] } ?: 0
        val confidence = output[topIndex]
        if (confidence < ANOMALY_CONFIDENCE_THRESHOLD) {
            notificationScheduler.scheduleModelAnomalyAlert(
                title = "VVC $skillName: anomalía",
                message = "Clasificación local con baja confianza: ${"%.5f".format(Locale.US, confidence)}."
            )
        }
        return "${prefix}:CLASS=$topIndex;CONFIDENCE=${"%.5f".format(Locale.US, confidence)}"
    }

    companion object {
        private const val MODEL_DIRECTORY = "models"
        private const val DEFAULT_THREAD_COUNT = 2
        private const val MAX_AUDIO_SAMPLES = 16_000
        private const val MAX_IMAGE_VALUES = 224 * 224 * 3
        private const val MAX_PATTERN_VALUES = 512
        private const val IMAGE_SAMPLE_WIDTH = 224
        private const val IMAGE_SAMPLE_HEIGHT = 224
        private const val UNSIGNED_BYTE_MASK = 0xFF
        private const val UNSIGNED_BYTE_NORMALIZER = 255.0f
        private const val ACTION_INIT_PENDING = -10
        private const val ACTION_MODEL_NOT_AVAILABLE = -11
        private const val ACTION_INTERPRETER_NOT_AVAILABLE = -12
        private const val ACTION_EMPTY_RESULT = -13
        private const val ANOMALY_CONFIDENCE_THRESHOLD = 0.35f
        private const val HASH_PREVIEW_LENGTH = 12
        private val AUDIO_MODEL_MARKERS = arrayOf("audio", "scribe", "speech", "asr", "whisper", "yamnet")
        private val IMAGE_MODEL_MARKERS = arrayOf("image", "vision", "ask", "visual", "capture", "mobilenet", "paligemma")
        private val ACTION_MODEL_MARKERS = arrayOf("action", "gesture", "intent", "mobile", "control", "text")
    }
}
