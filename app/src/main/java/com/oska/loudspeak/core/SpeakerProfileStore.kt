package com.oska.loudspeak.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 一个已登记的说话人声纹。emb = 该人声音的声纹向量（CAM++ 192 维等）。 */
data class SpeakerProfile(
    val id: String,
    val name: String,
    val emb: FloatArray,
    val createdAt: Long,
    /** 是否做声纹标注（句首加「名字：」前缀）。默认开；关掉 = 这人的话不加名字（文字照样转写、照样标注） */
    val annotate: Boolean = true,
    /** 是否对该声纹做音频转文字。默认开；关掉 = 这人说的整句滤过不显示 */
    val transcribe: Boolean = true
)

/**
 * 声纹档案持久化：<modelsDir>/spk/profiles.json
 * 支持任意多个人（A/B/C/爸爸/妈妈…），每人一条。
 */
class SpeakerProfileStore(private val modelsDir: String) {

    fun file(): File = File(File(modelsDir, "spk").apply { mkdirs() }, "profiles.json")

    fun load(): MutableList<SpeakerProfile> {
        val f = file()
        if (!f.isFile) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<SpeakerProfile>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ea = o.getJSONArray("emb")
                val emb = FloatArray(ea.length()) { ea.getDouble(it).toFloat() }
                out.add(SpeakerProfile(o.getString("id"), o.getString("name"), emb, o.optLong("createdAt"), o.optBoolean("annotate", true), o.optBoolean("transcribe", true)))
            }
            out
        } catch (e: Exception) {
            Log.e("SpeakerProfileStore", "load failed", e)
            mutableListOf()
        }
    }

    fun save(list: List<SpeakerProfile>): Boolean = try {
        val arr = JSONArray()
        list.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("createdAt", p.createdAt)
            o.put("annotate", p.annotate)
            o.put("transcribe", p.transcribe)
            val ea = JSONArray()
            p.emb.forEach { ea.put(it.toDouble()) }
            o.put("emb", ea)
            arr.put(o)
        }
        file().writeText(arr.toString())
        true
    } catch (e: Exception) {
        Log.e("SpeakerProfileStore", "save failed", e); false
    }

    /** 新增一个人；若与已有人声纹维度不一致（换了模型），拒绝并返回 false */
    fun add(name: String, emb: FloatArray): Boolean {
        if (emb.isEmpty()) return false
        val list = load()
        if (list.isNotEmpty() && list[0].emb.size != emb.size) return false
        list.add(SpeakerProfile(UUID.randomUUID().toString(), name, emb, System.currentTimeMillis(), true, true))
        return save(list)
    }

    fun remove(id: String): Boolean {
        val l = load(); l.removeAll { it.id == id }; return save(l)
    }

    fun rename(id: String, name: String): Boolean {
        val l = load()
        val i = l.indexOfFirst { it.id == id }
        if (i >= 0) l[i] = l[i].copy(name = name)
        return save(l)
    }

    /** 改某人的两个声纹开关（标注 / 转文字），只动这一个人，不覆盖整表 */
    fun setFlags(id: String, annotate: Boolean, transcribe: Boolean): Boolean {
        val l = load()
        val i = l.indexOfFirst { it.id == id }
        if (i >= 0) { l[i] = l[i].copy(annotate = annotate, transcribe = transcribe); return save(l) }
        return false
    }

    /** 只改「声纹标注」开关（不碰另一个，避免闭包拿到旧值把对方冲掉） */
    fun setAnnotate(id: String, v: Boolean): Boolean {
        val l = load()
        val i = l.indexOfFirst { it.id == id }
        if (i >= 0) { l[i] = l[i].copy(annotate = v); return save(l) }
        return false
    }

    /** 只改「转文字」开关（不碰另一个） */
    fun setTranscribe(id: String, v: Boolean): Boolean {
        val l = load()
        val i = l.indexOfFirst { it.id == id }
        if (i >= 0) { l[i] = l[i].copy(transcribe = v); return save(l) }
        return false
    }
}
