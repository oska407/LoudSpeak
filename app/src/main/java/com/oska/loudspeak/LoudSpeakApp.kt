package com.oska.loudspeak

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import com.oska.loudspeak.core.CrashReporter

class LoudSpeakApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)   // 全局崩溃捕获 → error_reports/*.txt（调试用）

        // 使用时不息屏：见 AwakeCallbacks 注释
        registerActivityLifecycleCallbacks(AwakeCallbacks)
    }

    /**
     * 为什么必须「常亮」：孩子是照着屏幕读/说的，动辄十几秒不碰屏，
     * 系统默认息屏（15–30 秒）会先把屏压暗再锁掉 —— 录到一半黑屏，
     * 孩子不明所以，音频采集也随之被系统掐掉。
     *
     * 为什么用 Application 钩子而不是每个 Activity 手写：一共 11 个页面，
     * 漏一个就是「进某个页会息屏」的偶发 bug；这里一次性覆盖，以后新页面自动生效。
     *
     * 为什么用 FLAG_KEEP_SCREEN_ON 而不是 PowerManager.WakeLock：
     * - 不需要 WAKE_LOCK 权限；
     * - 由系统按「窗口是否可见」托管，Activity 一走到 onPause（锁屏键 / 切后台）
     *   就自动失效，不会出现「忘记 release 导致一直在耗电」的经典坑；
     * - 只在设备有电且在前台时起作用，和它语义正好对上。
     *
     * 发热：FLAG_KEEP_SCREEN_ON 本身不增加 GPU/CPU 负载（屏本来就是亮的），
     * 真正热的是相机 + 编码器；录制的降亮度 50% / 60Hz 已经在 startRecording() 里处理。
     */
    private object AwakeCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) {
            a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onActivityPaused(a: Activity) {
            a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onActivityCreated(a: Activity, s: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
}
