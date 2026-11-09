package com.khilesh.camerapro

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var info: TextView
    private lateinit var executor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var flash = false
    private var videoMode = false
    private val requestCode = 81

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        executor = Executors.newSingleThreadExecutor()
        buildUi()
        if (hasPermissions()) startCamera() else ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), requestCode
        )
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))
        info = TextView(this).apply {
            text = "AI CAMERA PRO  •  PHOTO"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 10, 8, 6)
        }
        root.addView(info)
        val zoom = SeekBar(this).apply {
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) camera?.cameraControl?.setLinearZoom(progress / 100f)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(zoom, LinearLayout.LayoutParams(-1, 42))
        val row1 = controls()
        row1.addView(button("FLASH") { flash = !flash; camera?.cameraControl?.enableTorch(flash); toast(if (flash) "Flash ON" else "Flash OFF") }, weight())
        row1.addView(button("SWITCH") { lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK; startCamera() }, weight())
        row1.addView(button("PHOTO") { videoMode = false; info.text = "AI CAMERA PRO  •  PHOTO"; updateCaptureButton() }, weight())
        row1.addView(button("VIDEO") { videoMode = true; info.text = "AI CAMERA PRO  •  VIDEO"; updateCaptureButton() }, weight())
        root.addView(row1)
        val row2 = controls()
        row2.addView(button("TAKE PHOTO") { takePhoto() }, weight(2f))
        row2.addView(button("●  RECORD") { toggleVideo() }, weight(2f))
        root.addView(row2)
        setContentView(root)
        previewView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                camera?.cameraControl?.startFocusAndMetering(action)
                toast("Focus set")
            }
            true
        }
        updateCaptureButton()
    }

    private fun controls() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(8, 5, 8, 10)
    }
    private fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, 52, w)
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(34, 44, 58))
        setOnClickListener { action() }
    }
    private fun updateCaptureButton() { }

    private fun hasPermissions() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == this.requestCode && hasPermissions()) startCamera()
        else if (requestCode == this.requestCode) {
            toast("Camera aur microphone permission zaroori hai")
            finish()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = CameraSelector.Builder().requireLensFacing(lens).build()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .build()
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()
            videoCapture = VideoCapture.withOutput(recorder)
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
                camera?.cameraControl?.enableTorch(flash)
            } catch (e: Exception) {
                toast("Camera start nahi hua: ${e.localizedMessage}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return toast("Camera ready nahi hai")
        val name = "AICamera_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AI Camera Pro")
        }
        val options = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = runOnUiThread { toast("Photo Gallery mein save ho gayi") }
            override fun onError(exception: ImageCaptureException) = runOnUiThread { toast("Photo error: ${exception.message}") }
        })
    }

    private fun toggleVideo() {
        val active = recording
        if (active != null) {
            active.stop()
            recording = null
            return
        }
        val capture = videoCapture ?: return toast("Video camera ready nahi hai")
        val name = "AICamera_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AI Camera Pro")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        var pending: PendingRecording = capture.output.prepareRecording(this, output)
        pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                toast(if (event.hasError()) "Video save error: ${event.error}" else "Video Gallery mein save ho gaya")
            }
        }
        toast("Recording started • Stop dabakar save karein")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    override fun onDestroy() {
        recording?.stop()
        executor.shutdown()
        super.onDestroy()
    }
}
