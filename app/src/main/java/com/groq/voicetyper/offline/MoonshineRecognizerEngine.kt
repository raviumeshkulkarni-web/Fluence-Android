package com.groq.voicetyper.offline

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/**
 * Concrete implementation of RecognizerEngine for Moonshine Base v1.
 * Uses the same sherpa-onnx OfflineRecognizer as SenseVoice, but with
 * Moonshine-specific model configuration.
 */
class MoonshineRecognizerEngine : RecognizerEngine {
    private var recognizer: OfflineRecognizer? = null

    override fun initialize(modelDir: String, numThreads: Int) {
        val preprocessorPath = "$modelDir/${MoonshineModelManager.PREPROCESSOR_FILENAME}"
        val encoderPath = "$modelDir/${MoonshineModelManager.ENCODER_FILENAME}"
        val uncachedDecoderPath = "$modelDir/${MoonshineModelManager.UNCACHED_DECODER_FILENAME}"
        val cachedDecoderPath = "$modelDir/${MoonshineModelManager.CACHED_DECODER_FILENAME}"
        val tokensPath = "$modelDir/${MoonshineModelManager.TOKENS_FILENAME}"

        val moonshineConfig = OfflineMoonshineModelConfig(
            preprocessor = preprocessorPath,
            encoder = encoderPath,
            uncachedDecoder = uncachedDecoderPath,
            cachedDecoder = cachedDecoderPath
        )

        val modelConfig = OfflineModelConfig(
            moonshine = moonshineConfig,
            tokens = tokensPath,
            numThreads = numThreads,
            provider = "cpu",
            debug = false
        )

        val config = OfflineRecognizerConfig(
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )

        recognizer = OfflineRecognizer(null, config)
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val engine = recognizer ?: return ""
        var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
        try {
            stream = engine.createStream()
            stream.acceptWaveform(samples, sampleRate)
            engine.decode(stream)
            val result = engine.getResult(stream)
            return result.text.trim()
        } finally {
            try {
                stream?.release()
            } catch (e: Exception) {
                Log.w("MoonshineRecognizerEngine", "Error releasing stream", e)
            }
        }
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
    }
}
