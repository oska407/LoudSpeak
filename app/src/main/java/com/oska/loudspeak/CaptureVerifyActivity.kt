package com.oska.loudspeak

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.util.Rational
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.oska.loudspeak.core.Ui
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * B1 采集验证页：CameraX 横屏 1280×720 采集，用 ViewPort 把预览与录制
 * 绑定到同一 3:4 中间竖条；录完用 MediaMetadataRetriever 读出真实分辨率。
 *
 * 目的（P0 验证项 1）：确认「预览 3:4 竖幅」与「录制文件分辨率」一致。
 * 若实测为全幅 1280×720，则说明本机前端不支持 544×720 直出，
 * 需到 B2 做编码级中间竖条裁切（设计已预见此路径）。
 */
class CaptureVerifyActivity : LsActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvResult: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var btnRecord: Button

    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var isRecording = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted[Manifest.permission.CAMERA] == true &&
                granted[Manifest.permission.RECORD_AUDIO] == true
        if (ok) startCamera() else {
            tvResult.text = getString(R.string.perm_hint)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_verify)
        Ui.hideStatusBar(this)

        previewView = findViewById(R.id.previewView)
        tvResult = findViewById(R.id.tvResult)
        tvVerdict = findViewById(R.id.tvVerdict)
        btnRecord = findViewById(R.id.btnRecord)

        btnRecord.setOnClickListener { toggleRecord() }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermitted()) startCamera()
        else permLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    private fun allPermitted(): Boolean {
        val c = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val a = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return c == PackageManager.PERMISSION_GRANTED && a == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()

                // 预览不设分辨率：视场由下面的 ViewPort(3:4) 统一决定
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                // 录制：只能用 QualitySelector 定分辨率。
                // VideoCapture.Builder.setTargetResolution() 是 @RestrictTo 死 API，
                // 调用会直接抛 UnsupportedOperationException("setTargetResolution is not supported.")
                // —— 这正是本页原来「相机绑定失败」的原因。
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()
                val vc = VideoCapture.Builder(recorder).build()
                videoCapture = vc

                // ViewPort：预览与录制共享同一 3:4 裁切视场
                val viewPort = ViewPort.Builder(
                    android.util.Rational(3, 4),
                    Surface.ROTATION_0
                ).setLayoutDirection(View.LAYOUT_DIRECTION_LTR).build()

                val group = UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(vc)
                    .build()

                val selector = CameraSelector.DEFAULT_FRONT_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, group)
            } catch (e: Exception) {
                tvResult.text = "相机绑定失败：${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleRecord() {
        if (isRecording) stopRecord() else startRecord()
    }

    private fun startRecord() {
        val vc = videoCapture ?: run {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(getExternalCacheDir(), "b1_${System.currentTimeMillis()}.mp4")
        val opts = FileOutputOptions.Builder(file).build()

        try {
            activeRecording = vc.output
                .prepareRecording(this, opts)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                            readFile(file)
                        } else {
                            tvResult.text = "录制失败，错误码 ${event.error}"
                            setRecordingUi(false)
                        }
                    }
                }
            setRecordingUi(true)
            tvResult.text = "录制中…（录 30 秒后点停止）"
            tvVerdict.text = ""
        } catch (e: Exception) {
            tvResult.text = "启动录制失败：${e.localizedMessage}"
        }
    }

    private fun stopRecord() {
        activeRecording?.stop()
        activeRecording = null
        setRecordingUi(false)
    }

    private fun setRecordingUi(rec: Boolean) {
        isRecording = rec
        btnRecord.text = if (rec) getString(R.string.btn_stop_rec) else getString(R.string.btn_start_rec)
    }

    private fun readFile(file: File) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            retriever.release()

            val sizeMb = file.length() / 1024f / 1024f
            val dur = durMs / 1000f

            val aspect = if (w != null && h != null && h > 0) w.toFloat() / h else 0f
            val isVertical = aspect in 0.70f..0.82f  // 3:4 ≈ 0.75

            tvResult.text = buildString {
                append("实测分辨率：${w ?: "?"}×${h ?: "?"}\n")
                append("宽高比：%.3f（3:4≈0.750）\n".format(aspect))
                append("时长：%.1f 秒\n".format(dur))
                append("文件大小：%.2f MB\n".format(sizeMb))
                append("路径：${file.name}")
            }

            tvVerdict.text = if (isVertical) {
                getString(R.string.verdict_pass)
            } else {
                getString(R.string.verdict_fail_full)
            }
            tvVerdict.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isVertical) R.color.accent else R.color.red
                )
            )
        } catch (e: Exception) {
            tvResult.text = "读取文件失败：${e.localizedMessage}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeRecording?.stop()
        cameraExecutor.shutdown()
    }
}
