package com.oska.loudspeak.core

import java.io.File

/**
 * 离线自动判定（§R9「完成离线时的 qwen 的自动判定」）。
 *
 * 两套后端，同一条接口：
 *  1) 规则后端（默认、永远可用、纯离线）：基于 RuleEngine 的代词/口头禅/句式问题，
 *     直接生成「本场改进评语」与每句追问语。不需要任何模型文件、不需要联网。
 *  2) 本地 Qwen 后端（可选、默认关）：若用户拷入了 Qwen GGUF 且开启了「本地 Qwen」开关，
 *     走 LlamaRunner 在手机上本地推理，产出更自然的评语；
 *     一旦加载/推理失败，**自动回退到规则后端**，绝不卡住判定流程。
 *
 * 设计约定（§5.7）：Qwen 默认关闭、录后按需加载、判完立即释放；
 * 能否真正在 A3x 上跑通由 P0 真机实测决定（无 NPU、6GB 内存，0.6B Q4 约 400MB）。
 */
object QwenJudge {

    /**
     * 判定入口。
     * @param useLlm 设置里「本地 Qwen」是否开启
     * @param ggufPath 本地 Qwen 模型路径（null = 没就位）
     * @return 总评语（可空，null=无内容） + 每句判定文本（index→文本，可空表示该句无需判定）
     */
    fun judge(
        sentences: List<RuleEngine.Sentence>,
        useLlm: Boolean,
        ggufPath: String?
    ): JudgeResult {
        val problems = sentences.filter { it.isProblem }
        val perSentence = HashMap<Int, String?>()

        // 规则后端：先给每句问题句填上规则追问语（Qwen 没接上时它就是最终判定）
        for (s in problems) perSentence[s.index] = s.followUp.takeIf { it.isNotEmpty() }

        val overallRules = buildOverallRules(sentences)

        // 本地 Qwen 后端：能接上就用它覆盖规则结果；接不上（文件/库/推理任一失败）静默回退
        if (useLlm && ggufPath != null && LlamaRunner.available) {
            try {
                val prompt = buildPrompt(sentences)
                val llmText = LlamaRunner.generate(prompt, ggufPath)
                if (llmText != null) {
                    val parsed = parseLlm(llmText, sentences)
                    if (parsed.first != null) {
                        return JudgeResult(parsed.first, parsed.second.takeIf { it.isNotEmpty() } ?: perSentence)
                    }
                }
            } catch (e: Exception) {
                CrashReporter.report("warn", "本地 Qwen 判定回退到规则：${e.message?.take(120)}", e)
            }
        }
        return JudgeResult(overallRules, perSentence)
    }

    /** 规则总评语（离线、确定可用） */
    private fun buildOverallRules(sentences: List<RuleEngine.Sentence>): String {
        val total = sentences.size
        val ok = sentences.count { !it.uncertain }
        val pron = sentences.sumOf { it.pronouns.size }
        val fill = sentences.sumOf { it.fillers.size }
        val incomplete = sentences.count { it.isProblem && it.pronouns.isEmpty() }
        return buildString {
            append("本次共 ${total} 句，说清楚 ${ok} 句。")
            if (pron > 0) append("用了 $pron 处代词，下次试试直接说出名字；")
            if (fill > 0) append("$fill 处口头禅可以省掉；")
            if (incomplete > 0) append("有 $incomplete 句没说完，把话说完整。")
            if (pron == 0 && fill == 0 && incomplete == 0) append("整体讲得很清楚，继续保持。")
        }
    }

    /** 给本地 Qwen 的提示（注意：这是**输入文本**，不是「提示词工程」；模型只按它读到的转录做判断） */
    private fun buildPrompt(sentences: List<RuleEngine.Sentence>): String {
        val lines = sentences.map {
            if (it.uncertain) "（听不清）" else it.text
        }
        return "下面是孩子的一段口头表达，逐句列出。请只读这些内容，指出哪里用了代词（这个/那个/它）、" +
                "哪里有口头禅（嗯/啊）、哪句没说完整。用中文、分点、不超过 4 句。\n" +
                lines.joinToString(" / ")
    }

    /** 解析 Qwen 返回的自由文本：整体评语放 first，逐句判定暂不强解析（保留整体评语即可） */
    private fun parseLlm(text: String, sentences: List<RuleEngine.Sentence>): Pair<String?, HashMap<Int, String>> {
        val trimmed = text.trim().takeIf { it.isNotEmpty() } ?: return null to HashMap()
        // 不强行把模型输出拆回逐句（避免错配）；整体评语直接用，逐句仍走规则追问语
        return trimmed to HashMap()
    }
}

/** 判定结果 */
data class JudgeResult(
    val overall: String?,
    val perSentence: Map<Int, String?>
)

/**
 * 本地 Qwen（GGUF）推理运行器（§5.7 / §R9）。
 *
 * ⚠️ 真实推理依赖 llama.cpp 的 Android 原生库（libllama）与 Qwen3-0.6B GGUF 权重，
 * 二者都不在本仓库默认打包内；这部分是 **P0 真机验证项**（无 NPU 的 A3x 能否跑通 0.6B 待定）。
 * 此处只做「能接上就用、接不上就 null」的守卫式封装：
 *  - available=false 时 generate() 永不调用，绝不抛 UnsatisfiedLinkError 崩溃；
 *  - 上层（QwenJudge）在 null 时回退规则后端。
 * 真机验证通过、把 libllama 与 GGUF 接入后，把下方 TODO 实现即可启用。
 */
object LlamaRunner {
    val available: Boolean = try {
        // 没有这个库时不崩溃，只是标记为不可用；判定自动回退规则后端。
        System.loadLibrary("llama")
        true
    } catch (_: UnsatisfiedLinkError) { false }
      catch (_: Exception) { false }

    /** 本地推理（同步、后台线程调用）。未接入原生库时永远返回 null。 */
    @Suppress("UNUSED_PARAMETER")
    fun generate(prompt: String, ggufPath: String): String? {
        if (!available) return null
        return try {
            // TODO(P0)：调用已加载的 libllama 完成 0.6B 的 prompt→文本；
            // 当前占位，返回 null 让上层走规则后端。
            null
        } catch (_: Exception) { null }
    }
}
