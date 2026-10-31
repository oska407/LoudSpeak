package com.oska.loudspeak.core

import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * 说话人声纹引擎（3D-Speaker CAM++ 等）。
 *
 * 只做两件事：① 把一个 .onnx 声纹模型加载进来；② 从一段 16k 单声道 float PCM
 * 算出一个声纹向量。聚类/按名字比对交给上层（sherpa 自带 SpeakerEmbeddingManager）。
 *
 * 和 ASR 共用同一个 sherpa-onnx AAR（1.13.8），不额外加依赖。
 * 模型放置：<modelsDir>/spk/*.onnx（fp16 约 14MB / int8 约 8MB / fp32 约 27MB）。
 * 缺文件 → available=false；加载失败 → available=false 并如实写明原因。
 */
class SpeakerEngine(private val modelsDir: String) {

    var available = false; private set
    var status = "未加载"; private set
    var dim = 0; private set
    var loadedName = ""; private set

    private var extractor: SpeakerEmbeddingExtractor? = null

    fun spkDir(): File = File(modelsDir, "spk")

    /** spk/ 下的 .onnx，体积大的优先（通常精度更高） */
    fun findModels(): List<File> {
        val d = spkDir()
        if (!d.isDirectory) return emptyList()
        return (d.listFiles { f -> f.isFile && f.name.endsWith(".onnx", true) } ?: emptyArray())
            .sortedByDescending { it.length() }
    }

    fun load(file: File): Boolean {
        release()
        if (!file.isFile) { available = false; status = "找不到模型：${file.name}"; return false }
        if (file.length() < 1_000_000) {
            available = false; status = "模型文件异常（${file.length() / 1_000_000}MB）"; return false
        }
        return try {
            status = "加载中…"
            val cfg = SpeakerEmbeddingExtractorConfig(
                model = file.absolutePath,
                numThreads = 2
            )
            // assetManager 默认为 null → 走本地文件路径
            val ex = SpeakerEmbeddingExtractor(config = cfg)
            dim = ex.dim()
            extractor = ex
            available = true
            loadedName = file.name
            status = "就绪：${file.name}（${dim} 维）"
            true
        } catch (e: Throwable) {   // require(ptr != 0) 抛 IllegalArgumentException
            available = false
            status = "加载失败：${e.message?.take(120) ?: e.javaClass.simpleName}"
            Log.e("SpeakerEngine", "load failed", e)
            false
        }
    }

    /** 自动挑一个能用的加载（体积大的优先） */
    fun loadFirst(): Boolean {
        val f = findModels().firstOrNull()
        if (f == null) { available = false; status = "spk/ 下没有 .onnx 模型"; return false }
        return load(f)
    }

    /**
     * 试算一次（校验用）：喂 1 秒静音，跑通 compute 并核对维度。
     * 只验证「能加载 + 能推理」，不判断内容。
     */
    fun selfTest(): Pair<Boolean, String> {
        val ex = extractor ?: return false to "模型未加载"
        var stream: OnlineStream? = null
        return try {
            stream = ex.createStream()
            stream.acceptWaveform(FloatArray(16_000), 16_000)
            stream.inputFinished()
            val emb = ex.compute(stream)
            if (emb.isNotEmpty()) true to "试算通过：输出 ${emb.size} 维（模型声明 $dim 维）"
            else false to "试算失败：输出为空"
        } catch (e: Throwable) {
            false to "试算失败：${e.message?.take(120) ?: e.javaClass.simpleName}"
        } finally {
            try { stream?.release() } catch (_: Throwable) {}
        }
    }

    /** 从 16k 单声道 PCM 算声纹向量；失败返回 null */
    fun embed(pcm: FloatArray, sampleRate: Int = 16_000): FloatArray? {
        val ex = extractor ?: return null
        var stream: OnlineStream? = null
        return try {
            stream = ex.createStream()
            stream.acceptWaveform(pcm, sampleRate)
            stream.inputFinished()
            ex.compute(stream)
        } catch (e: Throwable) {
            Log.e("SpeakerEngine", "embed failed", e); null
        } finally {
            try { stream?.release() } catch (_: Throwable) {}
        }
    }

    fun release() {
        try { extractor?.release() } catch (_: Throwable) {}
        extractor = null
        available = false
        dim = 0
        loadedName = ""
    }
}
