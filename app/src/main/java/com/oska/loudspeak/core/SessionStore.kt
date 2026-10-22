package com.oska.loudspeak.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话存储（§10 / §11）：
 * - 会话目录：sessions/yyyyMMdd-HHmmss/{audio.wav, transcript.json, meta.json}（仅 ①② 档建目录）
 * - meta.json / transcript.json 字段按 §10.2 / §10.3；③④ 档一个字节都不写
 * - SQLite 索引表（§10.4）供历史列表快速加载
 * - 软删除：移入同根 .trash/，路径安全校验（只删本 App 写过的会话目录）
 */
class SessionStore(private val ctx: Context) : SQLiteOpenHelper(ctx, "loudspeak.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sessions(
            sessionId TEXT PRIMARY KEY, startedAt INTEGER, durationMs INTEGER,
            pronounCount INTEGER, completeCount INTEGER, incompleteCount INTEGER,
            selfMarkedCount INTEGER, exitKind TEXT, dirPath TEXT)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    private fun root(): String = Prefs.sessionsRoot(ctx)

    class Record(
        val sessionId: String, val startedAt: Long, val durationMs: Long,
        val pronounCount: Int, val completeCount: Int, val incompleteCount: Int,
        val selfMarkedCount: Int, val exitKind: String, val dirPath: String
    )

    /** 新建会话目录（仅 ①② 档调用；③④ 档不建 §10.1） */
    fun newSessionDir(startedAt: Long): File {
        val name = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(startedAt))
        val dir = File(root(), name)
        dir.mkdirs()
        return dir
    }

    /**
     * 写 meta.json（§10.2）。
     * cropBias：录这一条时的取景偏移 —— 写在 mp4 里的是全幅画面（见 MEMORY 约定），
     * 所以回放必须按**当时**的偏移显示，不能拿「现在」的偏移。
     */
    fun writeMeta(dir: File, mode: String, startedAt: Long, endedAt: Long, durationMs: Long,
                  take: Int, pronouns: Int, complete: Int, incomplete: Int, selfMarked: Int,
                  exitKind: String, modelsNote: String, cropBias: Float) {
        val o = JSONObject()
            .put("sessionId", dir.name)
            .put("mode", mode)
            .put("startedAt", startedAt).put("endedAt", endedAt).put("durationMs", durationMs)
            .put("pauseCount", 0)
            .put("take", take)
            .put("topic", JSONObject.NULL)
            .put("videoResolution", JSONObject.NULL)
            .put("models", JSONObject().put("asr", "SenseVoice int8").put("degraded", modelsNote))
            .put("stats", JSONObject()
                .put("pronounCount", pronouns).put("completeCount", complete)
                .put("incompleteCount", incomplete).put("selfMarkedCount", selfMarked))
            .put("exitKind", exitKind)
            .put("cropBias", cropBias.toDouble())
            .put("chapters", JSONArray())
            .put("schemaVersion", 2)
        File(dir, "meta.json").writeText(o.toString(2))
    }

    /** 写 transcript.json（§10.3 分句数组） */
    fun writeTranscript(dir: File, sentences: List<RuleEngine.Sentence>) {
        val arr = JSONArray()
        for (s in sentences) {
            arr.put(JSONObject()
                .put("index", s.index)
                .put("tStartMs", s.tStartMs).put("tEndMs", s.tEndMs)
                .put("text", if (s.uncertain) "?" else s.text)
                .put("words", JSONArray())
                .put("pronouns", JSONArray().apply { s.pronouns.forEach { put(JSONObject().put("word", it.first).put("at", it.second)) } })
                .put("flags", JSONArray().apply { s.flags.forEach { put(it) } })
                .put("judge", s.judge ?: JSONObject.NULL)
                .put("selfMarked", s.selfMarked)
                .put("take", s.take))
        }
        File(dir, "transcript.json").writeText(arr.toString(2))
    }

    /** 写 SQLite 索引行（§10.4） */
    fun indexSession(r: Record) {
        writableDatabase.insertWithOnConflict("sessions", null, ContentValues().apply {
            put("sessionId", r.sessionId); put("startedAt", r.startedAt)
            put("durationMs", r.durationMs)
            put("pronounCount", r.pronounCount); put("completeCount", r.completeCount)
            put("incompleteCount", r.incompleteCount); put("selfMarkedCount", r.selfMarkedCount)
            put("exitKind", r.exitKind); put("dirPath", r.dirPath)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 历史列表（时间倒序） */
    fun listSessions(): List<Record> {
        val out = ArrayList<Record>()
        readableDatabase.rawQuery("SELECT * FROM sessions ORDER BY startedAt DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(Record(c.getString(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getInt(4),
                    c.getInt(5), c.getInt(6), c.getString(7), c.getString(8)))
            }
        }
        return out
    }

    /**
     * 软删除（§11.4）：路径安全校验（必须位于 sessions 根内）+ meta 白名单校验，
     * 通过后移入 <root>/.trash/。返回 null=成功，否则错误文案。
     */
    fun softDelete(sessionId: String): String? {
        val rootF = File(root()).canonicalFile
        val dir = File(rootF, sessionId).canonicalFile
        if (!dir.path.startsWith(rootF.path)) return "路径校验失败（不在 sessions 内）"
        if (!dir.isDirectory) return "会话目录不存在"
        val meta = File(dir, "meta.json")
        val transcript = File(dir, "transcript.json")
        if (!meta.isFile || !transcript.isFile) return "白名单校验失败（缺 meta/transcript）"
        try {
            val o = JSONObject(meta.readText())
            if (o.optString("sessionId") != sessionId) return "白名单校验失败（sessionId 不一致）"
        } catch (e: Exception) { return "meta.json 解析失败" }
        val trash = File(rootF, ".trash/${sessionId}-${System.currentTimeMillis()}")
        if (!dir.renameTo(trash)) return "移动到回收站失败"
        writableDatabase.delete("sessions", "sessionId=?", arrayOf(sessionId))
        return null
    }

    /** 目录占用（字节）。§11.5：占用是「会话 + 回收站」一起算 */
    fun dirSizeBytes(f: File): Long = try {
        f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Exception) { 0L }

    fun trashSizeBytes(): Long = dirSizeBytes(File(root(), ".trash"))

    fun sessionsSizeBytes(): Long = dirSizeBytes(File(root()))

    /** 最旧的 N 条（§11.5 清理建议：列出供勾选，不替用户决定删哪些） */
    fun oldestSessions(n: Int): List<Record> = listSessions().sortedBy { it.startedAt }.take(n)

    /** 清理回收站中 7 天前的目录（§11.4，每日首次启动时调用） */
    fun cleanTrash() {
        val trash = File(root(), ".trash")
        val week = 7L * 24 * 3600 * 1000
        trash.listFiles()?.forEach {
            if (it.lastModified() + week < System.currentTimeMillis()) it.deleteRecursively()
        }
    }

    // ---- 回收站明细（§11.4：逐条恢复 / 永久删除 / 清空） ----

    class TrashItem(val dir: File, val deletedAt: Long)

    fun listTrash(): List<TrashItem> =
        File(root(), ".trash").listFiles()?.filter { it.isDirectory }
            ?.map { TrashItem(it, it.lastModified()) }
            ?.sortedByDescending { it.deletedAt } ?: emptyList()

    fun restoreFromTrash(item: TrashItem): String? {
        // 目录名必须原样还原成 sessionId：回收站里是 "<sessionId>-<删除时间戳>"，
        // 若再拼一次时间戳，还原出来的目录名就不是 sessionId 了，下次删除会卡在路径校验。
        val metaNow = File(item.dir, "meta.json")
        val sessionId = try {
            if (metaNow.isFile) JSONObject(metaNow.readText()).optString("sessionId")
                .takeIf { it.isNotBlank() } else null
        } catch (_: Exception) { null } ?: item.dir.name.substringBeforeLast('-')
        val target = File(root(), sessionId)
        if (target.exists()) return "恢复失败（历史里已有同名会话：$sessionId）"
        return try {
            if (!item.dir.renameTo(target)) "恢复失败（目录被占用？）"
            else {
                // 重新登记索引
                val meta = File(target, "meta.json")
                if (meta.isFile) {
                    val o = JSONObject(meta.readText())
                    indexSession(Record(o.getString("sessionId"), o.optLong("startedAt"),
                        o.optLong("durationMs"),
                        o.getJSONObject("stats").optInt("pronounCount"),
                        o.getJSONObject("stats").optInt("completeCount"),
                        o.getJSONObject("stats").optInt("incompleteCount"),
                        o.getJSONObject("stats").optInt("selfMarkedCount"),
                        o.optString("exitKind", "normal"), target.absolutePath))
                }
                null
            }
        } catch (e: Exception) { "恢复失败：${e.message?.take(80)}" }
    }

    fun purgeTrashItem(item: TrashItem): Boolean = item.dir.deleteRecursively()

    fun emptyTrash(): Int {
        val trash = File(root(), ".trash")
        var n = 0
        trash.listFiles()?.forEach { if (it.deleteRecursively()) n++ }
        return n
    }
}
