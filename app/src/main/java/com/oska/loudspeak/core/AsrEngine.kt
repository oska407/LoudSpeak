package com.oska.loudspeak.core

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/**
 * SenseVoice 一次识别的完整结果。
 *
 * SenseVoice 除了文字，还顺带给出**声学层**的六项（不用额外模型、不额外耗时，
 * 就是同一个解码结果的几个字段）：
 *  - `tokens` / `timestamps`：每个字（token）及其起点（秒）—— R12「说得顺不顺」全靠它；
 *  - `lang` / `emotion` / `event`：语种 / 情绪 / 声音事件。
 *
 * ⚠️ 注意格式：sherpa 返回的 lang/emotion/event 是 `<|zh|>` 这种带尖括号的标签，
 * 这里一律用 [bare] 剥掉外壳，调用方拿到的直接是 `zh` / `NEUTRAL` / `Speech`。
 *
 * ⚠️ 这些副产品的上限：**它们是声学模型的输出，跨不过语义**。
 * 主谓宾、指代、句子完整度它零能力；并且噪声场景实测「达摩院」被认成「打摩院」时
 * event 依然返回 `Speech`（没报警），所以 event **不能**当识别可信度用。
 */
class AsrResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
    val lang: String,
    val emotion: String,
    val event: String
) {
    /**
     * 转成会话绝对毫秒数组，与 [tokens] 一一对应。
     *
     * ⚠️ 必须这一步：[timestamps] 是**相对本句 PCM 起点**的秒数，
     * 要跟别的句子比、要定位回放，都得先加上本句在整场里的起点 `t0`。
     */
    fun absMs(t0: Long): LongArray =
        LongArray(timestamps.size) { t0 + (timestamps[it] * 1000f).toLong() }

    /**
     * 与 [absMs] 对齐的字表（已剥壳）。长度取 min(tokens, timestamps)，
     * 免得极端情况下两边不等长把下标撑爆。
     */
    fun absWords(): Array<String> {
        val n = minOf(tokens.size, timestamps.size)
        val out = ArrayList<String>(n)
        for (i in 0 until n) out.add(bare(tokens[i]))
        return out.toTypedArray()
    }
}

/** 剥掉 `<|…|>` 外壳。每句都调用，所以用 substring 不用正则，省一次 Pattern 分配。 */
fun bare(s: String): String {
    if (!s.startsWith("<|")) return s
    val e = s.indexOf("|>")
    return if (e > 1) s.substring(2, e) else s
}

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
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
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

    /** 单句转写。输入 16kHz 单声道 float PCM。出错或空文本返回 null（UI 显示 ?） */
    fun transcribe(pcm: FloatArray): AsrResult? {
        val r = recognizer ?: return null
        var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
        return try {
            stream = r.createStream()
            stream.acceptWaveform(pcm, 16000)
            r.decode(stream)
            val res = r.getResult(stream)
            val text = res.text.trim()
            if (text.isEmpty()) null
            else AsrResult(
                text = text,
                tokens = res.tokens,
                timestamps = res.timestamps,
                lang = bare(res.lang),
                emotion = bare(res.emotion),
                event = bare(res.event)
            )
        } catch (e: Exception) {
            Log.e("AsrEngine", "transcribe failed", e)
            null
        } finally {
            // 必须释放：每句一个 stream，不释放会持续吃 native 内存（长时间会话会 OOM）
            try { stream?.release() } catch (_: Exception) {}
        }
    }

    fun release() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        available = false
        status = "已释放"
    }
}
