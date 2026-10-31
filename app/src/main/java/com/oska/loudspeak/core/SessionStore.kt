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
class SessionStore(private val ctx: Context) : SQLiteOpenHelper(ctx, "loudspeak.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sessions(
            sessionId TEXT PRIMARY KEY, startedAt INTEGER, durationMs INTEGER,
            pronounCount INTEGER, completeCount INTEGER, incompleteCount INTEGER,
            selfMarkedCount INTEGER, exitKind TEXT, dirPath TEXT,
            star INTEGER NOT NULL DEFAULT 0, title TEXT NOT NULL DEFAULT '')""")
    }

    /** v1 → v2：补「星标」列（§11.2 可标重点，便于回看）；v2 → v3：补「备注名」列（§7 改名） */
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) try {
            db.execSQL("ALTER TABLE sessions ADD COLUMN star INTEGER NOT NULL DEFAULT 0")
        } catch (_: Exception) { /* 已经加过了就不管 */ }
        if (o < 3) try {
            db.execSQL("ALTER TABLE sessions ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        } catch (_: Exception) { /* 已经加过了就不管 */ }
    }

    private fun root(): String = Prefs.sessionsRoot(ctx)

    class Record(
        val sessionId: String, val startedAt: Long, val durationMs: Long,
        val pronounCount: Int, val completeCount: Int, val incompleteCount: Int,
        val selfMarkedCount: Int, val exitKind: String, val dirPath: String,
        val star: Boolean = false, val title: String? = null
    )

    /** 历史排序（§11.2 E5）：0 时间倒序 / 1 不完整句最多优先 */
    companion object {
        const val ORDER_TIME = 0
        const val ORDER_INCOMPLETE = 1
    }

    /**
     * 新建会话目录（仅 ①② 档调用；③④ 档不建 §10.1）。
     * 命名：yyyyMMdd-HHmm（年-月-日-时-分，见 R9「视频音频文字识别一起存储」）。
     * 同一分钟内的第二条（或同一分钟 ①② 档各一条）会追加 -2 / -3 …… 防止互相覆盖。
     */
    fun newSessionDir(startedAt: Long): File {
        val base = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(startedAt))
        var n = 0
        while (true) {
            val name = if (n == 0) base else "$base-$n"
            val dir = File(root(), name)
            if (!dir.exists()) { dir.mkdirs(); return dir }
            n++
        }
    }

    /** 把会话目录名（yyyyMMdd-HHmm 或带 -N 后缀）解析成人读的时间标签 */
    fun sessionLabel(sessionId: String): String {
        val base = sessionId.substringBefore('-')
        return try {
            val d = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).parse(base) ?: return sessionId
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(d)
        } catch (_: Exception) { sessionId }
    }

    /**
     * 写 meta.json（§10.2）。
     * cropBias：录这一条时的取景偏移 —— 写在 mp4 里的是全幅画面（见 MEMORY 约定），
     * 所以回放必须按**当时**的偏移显示，不能拿「现在」的偏移。
     */
    fun writeMeta(dir: File, mode: String, startedAt: Long, endedAt: Long, durationMs: Long,
                  take: Int, pronouns: Int, complete: Int, incomplete: Int, selfMarked: Int,
                  exitKind: String, modelsNote: String, cropBias: Float,
                  fluency: Fluency.Report? = null) {
        // R12「说得顺不顺」的聚合值。只存汇总，**不存逐字时间戳**（10 分钟约 24KB，没必要）。
        // 留着是为了将来画「练了两个月有没有进步」的趋势线 —— 单场看意义不大，跨会话才有。
        val fl = if (fluency != null) JSONObject()
            .put("sentences", fluency.nSentence)
            .put("words", fluency.nWord)
            .put("speakMs", fluency.speakMs)
            .put("cps", fluency.cps.toDouble())
            .put("maxLeadMs", fluency.maxLeadMs)
            .put("gapCount", fluency.nGapTotal)
            .put("gapMs", fluency.gapMs)
            .put("maxGapMs", fluency.gaps.firstOrNull()?.gapMs ?: 0L)
        else JSONObject.NULL
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
                .put("fluency", fl)
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
                .put("muzzy", JSONArray().apply { s.muzzy.forEach { put(JSONObject().put("word", it.first).put("at", it.second)) } })
                .put("flags", JSONArray().apply { s.flags.forEach { put(it) } })
                .put("judge", s.judge ?: JSONObject.NULL)
                .put("selfMarked", s.selfMarked)
                .put("punct", s.punct)          // 补的标点（不改字）
                .put("mergePrev", s.mergePrev)  // 并回上一句的碎片
                .put("lowVol", JSONArray().apply { s.lowVol.forEach { put(if (it) 1 else 0) } }) // 音量不达标逐字标注
                .put("speaker", s.speakerName)   // 声纹区分：谁说的（空=未接入/没认出）
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
            put("star", if (r.star) 1 else 0)
            put("title", r.title ?: "")
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 历史列表（§11.2）。
     * @param order ORDER_TIME 时间倒序 / ORDER_INCOMPLETE 不完整句最多优先
     * @param onlyStar 只看星标
     * @param sinceMs / untilMs 日期范围（0 = 不限）
     */
    fun listSessions(
        order: Int = ORDER_TIME,
        onlyStar: Boolean = false,
        sinceMs: Long = 0L,
        untilMs: Long = 0L
    ): List<Record> {
        val where = StringBuilder()
        val args = ArrayList<String>()
        if (onlyStar) where.append("star=1")
        if (sinceMs > 0) { if (where.isNotEmpty()) where.append(" AND "); where.append("startedAt>=?"); args.add(sinceMs.toString()) }
        if (untilMs > 0) { if (where.isNotEmpty()) where.append(" AND "); where.append("startedAt<=?"); args.add(untilMs.toString()) }
        val sql = "SELECT sessionId,startedAt,durationMs,pronounCount,completeCount,incompleteCount," +
                "selfMarkedCount,exitKind,dirPath,star,title FROM sessions" +
                (if (where.isNotEmpty()) " WHERE $where" else "") +
                when (order) {
                    ORDER_INCOMPLETE -> " ORDER BY incompleteCount DESC, startedAt DESC"
                    else -> " ORDER BY startedAt DESC"
                }
        val out = ArrayList<Record>()
        readableDatabase.rawQuery(sql, if (args.isEmpty()) null else args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(Record(c.getString(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getInt(4),
                    c.getInt(5), c.getInt(6), c.getString(7), c.getString(8), c.getInt(9) == 1,
                    c.getString(10).takeIf { it.isNotBlank() }))
            }
        }
        return out
    }

    /** 星标（§11.2 / §11.3）：标记重点，便于回看 */
    fun setStar(sessionId: String, star: Boolean) {
        writableDatabase.execSQL("UPDATE sessions SET star=? WHERE sessionId=?",
            arrayOf(if (star) 1 else 0, sessionId))
    }

    /**
     * 从 transcript.json 重建句子序列（查看/回放页用，§R9）。
     * 还原 text / uncertain / pronouns / flags / selfMarked，足够 Annotator 重新排版。
     */
    fun loadSentences(dir: File): List<RuleEngine.Sentence> {
        val f = File(dir, "transcript.json")
        if (!f.isFile) return emptyList()
        return try {
            val arr = org.json.JSONArray(f.readText())
            val out = ArrayList<RuleEngine.Sentence>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val text = o.optString("text", "?")
                val uncertain = o.optBoolean("uncertain", text == "?")
                val s = RuleEngine.Sentence(
                    index = o.optInt("index", i),
                    tStartMs = o.optLong("tStartMs"),
                    tEndMs = o.optLong("tEndMs"),
                    text = if (uncertain) "?" else text,
                    uncertain = uncertain,
                    selfMarked = o.optBoolean("selfMarked", false)
                )
                s.speakerName = o.optString("speaker", "")
                val pr = o.optJSONArray("pronouns")
                if (pr != null) for (j in 0 until pr.length()) {
                    val p = pr.getJSONObject(j)
                    s.pronouns.add(p.optString("word") to p.optInt("at"))
                }
                val fl = o.optJSONArray("flags")
                if (fl != null) for (j in 0 until fl.length()) s.flags.add(fl.getString(j))
                s.punct = o.optString("punct", "")
                s.mergePrev = o.optBoolean("mergePrev", false)
                val lv = o.optJSONArray("lowVol")
                if (lv != null) s.lowVol = BooleanArray(lv.length()) { lv.optInt(it) == 1 }
                val mz = o.optJSONArray("muzzy")
                if (mz != null) for (j in 0 until mz.length()) {
                    val p = mz.getJSONObject(j)
                    s.muzzy.add(p.optString("word") to p.optInt("at"))
                }
                out.add(s)
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    /**
     * 软删除（§11.4）：路径安全校验（必须位于 sessions 根内）+ meta 白名单校验，
     * 通过后移入 <root>/.trash/。返回 null=成功，否则错误文案。
     *
     * keepText（§11.4 E2）：只删视频、留下转写文字。视频约 5.3MB/分钟，文字才几十 KB，
     * 有长期价值 —— 所以这条是可选项，默认关。
     */
    fun softDelete(sessionId: String, keepText: Boolean = Prefs.keepTextOnDelete(ctx)): String? {
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
        if (keepText) {
            // 只删画面/声音，transcript/meta 留着；标注一点不丢
            dir.listFiles { f: File -> f.isFile && (f.name.endsWith(".mp4") || f.name.endsWith(".wav")) }
                ?.forEach { it.delete() }
        }
        val star = try {
            readableDatabase.rawQuery("SELECT star FROM sessions WHERE sessionId=?", arrayOf(sessionId)).use {
                if (it.moveToFirst()) it.getInt(0) == 1 else false
            }
        } catch (_: Exception) { false }
        // ⚠️ 必须先把 .trash 建出来：renameTo 不会自动建父目录。
        // 旧代码没建过，全新安装头一次删就 rename 失败 → 删除静默失效（2026-10-23 修）。
        val trashDir = File(rootF, ".trash").apply { mkdirs() }
        val trash = File(trashDir, "${sessionId}-${System.currentTimeMillis()}")
        val moved = dir.renameTo(trash) || runCatching {
            dir.copyRecursively(trash, overwrite = true); dir.deleteRecursively()
        }.getOrDefault(false)
        if (!moved) return "移动到回收站失败"
        // 星标写进 meta，回收站里恢复回来还认得（DB 行会删掉）
        try {
            val mf = File(trash, "meta.json")
            val o = JSONObject(mf.readText()).put("star", star)
            mf.writeText(o.toString(2))
        } catch (_: Exception) {}
        writableDatabase.delete("sessions", "sessionId=?", arrayOf(sessionId))
        return null
    }

    /**
     * 迁移已有记录到新目录（§11.1「提供迁移已有记录按钮」）。
     * 规则：用户主动触发才搬；搬完才把设置切过去；跨分区 rename 失败时退化为复制+删除。
     * @return 成功条数 to 错误文案（null = 没问题）
     */
    fun migrateTo(newRootPath: String): Pair<Int, String?> {
        val src = File(root()).canonicalFile
        val dst = File(newRootPath).canonicalFile
        if (src.path == dst.path) return 0 to "新目录和当前目录是同一个"
        val r = moveChildren(src, dst)
        if (r.first > 0) storeRoot(dst.absolutePath)
        return r
    }

    /** 把某个旧目录里的记录搬进当前目录（默认目录 → 自定义目录时用） */
    fun migrateFrom(srcRootPath: String): Pair<Int, String?> =
        moveChildren(File(srcRootPath).canonicalFile, File(root()).canonicalFile)

    private fun moveChildren(src: File, dst: File): Pair<Int, String?> {
        if (src.path == dst.path) return 0 to "两个目录相同"
        try {
            dst.mkdirs()
            val probe = File(dst, ".probe")
            probe.writeText("ok"); probe.delete()
        } catch (e: Exception) {
            return 0 to "目标目录不可写：${e.message?.take(60)}"
        }
        if (!src.isDirectory) return 0 to null
        var n = 0
        var firstErr: String? = null
        src.listFiles()?.forEach { child ->
            val target = File(dst, child.name)
            if (target.exists()) return@forEach
            val ok = if (child.renameTo(target)) true else runCatching {
                child.copyRecursively(target, overwrite = true); child.deleteRecursively()
            }.getOrDefault(false)
            if (ok) {
                n++
                // 索引里的绝对路径必须跟着改，否则「查看」会找不到文件
                if (child.isDirectory && child.name != ".trash") {
                    try {
                        writableDatabase.execSQL("UPDATE sessions SET dirPath=? WHERE sessionId=?",
                            arrayOf(target.absolutePath, child.name))
                    } catch (_: Exception) {}
                }
            } else if (firstErr == null) firstErr = "「${child.name}」没搬过去"
        }
        return n to firstErr
    }

    /** 把会话根切到新目录（不搬文件，只影响新会话）。§11.1 */
    fun switchRoot(newRootPath: String) {
        storeRoot(newRootPath)
        File(newRootPath).mkdirs()
    }

    /**
     * 落「自定义目录」这个设置。如果选的其实就是默认目录，就清空成「默认」，
     * 免得界面上明明用的默认目录、却标着「自定义」。
     */
    private fun storeRoot(path: String) {
        val def = runCatching { File(Prefs.defaultSessionsRoot(ctx)).canonicalPath }.getOrDefault("")
        val p = runCatching { File(path).canonicalPath }.getOrDefault(path)
        Prefs.setSessionsRootCustom(ctx, if (p == def) "" else p)
    }

    /** 目录可写吗（新建 + 试写一个探针文件），切换存放位置前必查 */
    fun canWrite(path: String): String? = try {
        val d = File(path)
        d.mkdirs()
        val p = File(d, ".probe")
        p.writeText("ok"); p.delete()
        null
    } catch (e: Exception) { e.message?.take(80) ?: "不可写" }

    /** 目录占用（字节）。§11.5：占用是「会话 + 回收站」一起算 */
    fun dirSizeBytes(f: File): Long = try {
        f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Exception) { 0L }

    fun trashSizeBytes(): Long = dirSizeBytes(File(root(), ".trash"))

    fun sessionsSizeBytes(): Long = dirSizeBytes(File(root()))

    /** 最旧的 N 条（§11.5 清理建议：列出供勾选，不替用户决定删哪些） */
    fun oldestSessions(n: Int): List<Record> = listSessions().sortedBy { it.startedAt }.take(n)

    /** 清理回收站中过期目录（§11.4 E1：默认 7 天，每日首次启动时调用） */
    fun cleanTrash() {
        val trash = File(root(), ".trash")
        val keep = Prefs.TRASH_KEEP_DAYS * 24L * 3600 * 1000
        trash.listFiles()?.forEach {
            if (it.lastModified() + keep < System.currentTimeMillis()) it.deleteRecursively()
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
                        o.optString("exitKind", "normal"), target.absolutePath,
                        o.optBoolean("star", false),
                        o.optString("title", "").takeIf { it.isNotBlank() }))
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

    // ---- 备注名（§7 改名）：只动 meta.json 的 title 字段，目录名完全不动 ----

    /** 读备注名（meta.json.title）；无则 null */
    fun readTitle(dir: File): String? = try {
        val o = JSONObject(File(dir, "meta.json").readText())
        o.optString("title", "").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /** 写备注名：只改 meta.json 的 title 一个字段，不重写整份会话 */
    fun setTitle(dir: File, title: String) {
        try {
            val f = File(dir, "meta.json")
            if (!f.isFile) return
            val o = JSONObject(f.readText())
            o.put("title", title)
            f.writeText(o.toString(2))
        } catch (_: Exception) {}
    }

    /** 备注名同步进索引表，列表直接读、不必逐条解 meta.json */
    fun setTitleInDb(sessionId: String, title: String?) {
        writableDatabase.execSQL("UPDATE sessions SET title=? WHERE sessionId=?",
            arrayOf(title ?: "", sessionId))
    }
}
