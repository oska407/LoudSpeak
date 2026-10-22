package com.oska.loudspeak

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.oska.loudspeak.databinding.ActivityMainBinding

/**
 * M0 空壳：只验证「能编译、能装机、能看到 v6 布局骨架」。
 * 真实采集 / 转写 / 判定 / 回放在后续批次（M1 真机验证 → M2 采集转写 → M3 四档 → M4 判定）接入。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val buttons = listOf(
            binding.btnStop to "停止",
            binding.btnRec to "录音",
            binding.btnPlay to "播放",
            binding.btnHistory to "历史",
            binding.btnSettings to "设置",
            binding.btnMode to "模式",
        )
        buttons.forEach { (btn, name) ->
            btn.setOnClickListener {
                Toast.makeText(this, "$name（M0 占位）", Toast.LENGTH_SHORT).show()
            }
        }

        // 验证期入口：进入 B0 内存自检页（产品版将移除）
        binding.btnSelfCheck.setOnClickListener {
            startActivity(Intent(this, SelfCheckActivity::class.java))
        }
    }
}
