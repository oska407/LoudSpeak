package com.oska.loudspeak.core

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/**
 * SenseVoice 离线识别（sherpa-onnx 官方 AAR，模型外部放置 §5.3）。
 * 期望文件：<modelsDir>/asr/model.int8.onnx + asr/tokens.txt（从 hf-mirror 下载，见 docs/模型下载说明.md）。
 * 缺件 → available=false（E3，阻断四档出字）；加载失败 → available=false，UI 如实显示。
 */
class AsrEngine(private val modelsDir: String) {

    var available = false; private set
    var status = "未加载"; private set
    private var recognizer: OfflineRecognizer? = null

    fun checkFiles(): Pair<File?, File?> {
        val m = File(modelsDir, "asr/model.int8.onnx")
        val t = File(modelsDir, "asr/tokens.txt")
        return m to t
    }

    /** 加载。eMMC 上预估 15–30s（§5.5），必须在后台线程调用。 */
    fun load(): Boolean {
        try {
            val (model, tokens) = checkFiles()
            if (model == null || !model.isFile || tokens == null || !tokens.isFile) {
                available = false
                status = "缺模型文件：${if (model?.isFile != true) "asr/model.int8.onnx " else ""}${if (tokens?.isFile != true) "asr/tokens.txt" else ""}"
                return false
            }
            if (model.length() < 1_000_000) { available = false; status = "模型文件异常（${model.length() / 1_000_000}MB）"; return false }
            status = "加载中…"
            val config = OfflineRecognizerConfig(
                featureConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    modelType = "sense-voice",
                    tokens = tokens.absolutePath,
                    numThreads = 2,          // §13.3 措施 2：intra_op 钉死 2
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = model.absolutePath,
                        language = "",       // 空 = 自动（中英混）
                        useInverseTextNormalization = true
                    )
                )
            )
            recognizer = OfflineRecognizer(assetManager = null, config = config)
            available = true
            status = "就绪"
            return true
        } catch (e: Exception) {
            available = false
            status = "加载失败：${e.message?.take(120)}"
            Log.e("AsrEngine", "load failed", e)
            return false
        }
    }

    /** 单句转写。输入 16kHz 单声道 float PCM。出错返回 null（UI 显示 ?） */
    fun transcribe(pcm: FloatArray): String? {
        val r = recognizer ?: return null
        return try {
            val stream = r.createStream()
            stream.acceptWaveform(pcm, 16000)
            r.decode(stream)
            val text = r.getResult(stream).text.trim()
            stream.release()
            text.ifEmpty { null }
        } catch (e: Exception) {
            Log.e("AsrEngine", "transcribe failed", e)
            null
        }
    }

    fun release() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        available = false
        status = "已释放"
    }
}
