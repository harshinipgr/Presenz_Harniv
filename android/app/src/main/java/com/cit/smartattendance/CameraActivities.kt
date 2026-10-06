package com.cit.smartattendance

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Starts CameraX with a preview and delivers every frame as an upright Bitmap
 * to [onFrame] on a background thread.
 */
class CameraHelper(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val preferFront: Boolean,
    private val onError: (String) -> Unit,
    private val onFrame: (Bitmap) -> Unit
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun start() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    try {
                        val bmp = proxy.toBitmap()
                        val rotation = proxy.imageInfo.rotationDegrees
                        val upright = if (rotation != 0) {
                            val m = Matrix().apply { postRotate(rotation.toFloat()) }
                            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                        } else bmp
                        onFrame(upright)
                    } catch (e: Exception) {
                        Log.w("CameraHelper", "Frame error", e)
                    } finally {
                        proxy.close()
                    }
                }
                val hasFront = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
                val hasBack = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
                val selector = when {
                    preferFront && hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
                    !preferFront && hasBack -> CameraSelector.DEFAULT_BACK_CAMERA
                    hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
                    hasBack -> CameraSelector.DEFAULT_BACK_CAMERA
                    else -> { onError("No camera found on this device."); return@addListener }
                }
                provider.unbindAll()
                provider.bindToLifecycle(activity, selector, preview, analysis)
            } catch (e: Exception) {
                onError("Could not start the camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun shutdown() = executor.shutdown()
}

/**
 * On-device face embedding with MobileFaceNet (TensorFlow Lite).
 * Model input: [2,112,112,3] float, output: [2,192] embeddings. We feed the same face twice and use the first result.
 */
class FaceEmbedder(context: Context) : Closeable {
    private val interpreter: Interpreter

    init {
        val bytes = context.assets.open("MobileFaceNet.tflite").use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    }

    fun embed(face: Bitmap): FloatArray {
        val size = 112
        val scaled = Bitmap.createScaledBitmap(face, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        val input = ByteBuffer.allocateDirect(2 * size * size * 3 * 4).order(ByteOrder.nativeOrder())
        repeat(2) {
            for (p in pixels) {
                input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
                input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
        }
        input.rewind()
        val output = Array(2) { FloatArray(192) }
        interpreter.run(input, output)
        val v = output[0]
        val norm = sqrt(v.fold(0f) { s, x -> s + x * x }).coerceAtLeast(1e-10f)
        return FloatArray(v.size) { v[it] / norm }
    }

    override fun close() = interpreter.close()
}

/**
 * Face capture with LIVENESS: the student must blink and turn their head (random order),
 * then look straight while we compute the face embedding.
 * MODE_ENROLL collects 3 samples (sign-up); MODE_VERIFY collects 1 sample (attendance).
 */
class FaceCaptureActivity : BaseActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_EMBEDDINGS = "embeddings"
        const val MODE_ENROLL = "enroll"
        const val MODE_VERIFY = "verify"
        private const val TIMEOUT_MS = 45_000L
    }

    private enum class Step { BLINK, TURN, CAPTURE }

    private lateinit var camera: CameraHelper
    private lateinit var detector: FaceDetector
    private var embedder: FaceEmbedder? = null
    private lateinit var instruction: TextView
    private lateinit var stepsText: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var btnRetry: MaterialButton

    private val needed by lazy { if (intent.getStringExtra(EXTRA_MODE) == MODE_ENROLL) 3 else 1 }
    private var steps: List<Step> = emptyList()
    @Volatile private var stepIndex = 0
    @Volatile private var finished = false
    private var blinkPhase = 0
    private var turned = false
    private var lockedId: Int? = null
    private val samples = mutableListOf<FloatArray>()
    private var lastSampleAt = 0L
    private var startedAt = 0L

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showMessage("Camera needed", "Allow camera permission to capture your face.") { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_capture)
        setupToolbar(if (needed == 3) "Face enrolment" else "Face verification")
        instruction = findViewById(R.id.instructionText)
        stepsText = findViewById(R.id.stepsText)
        progressBar = findViewById(R.id.stepProgress)
        btnRetry = findViewById(R.id.btnRetry)
        btnRetry.setOnClickListener { resetAll(); btnRetry.visibility = View.GONE }

        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.2f)
                .enableTracking()
                .build()
        )
        try {
            embedder = FaceEmbedder(this)
        } catch (e: Exception) {
            showMessage("Face model missing", "MobileFaceNet.tflite could not be loaded from assets: ${e.message}") { finish() }
            return
        }
        camera = CameraHelper(this, findViewById(R.id.previewView), preferFront = true,
            onError = { msg -> runOnUiThread { showMessage("Camera", msg) { finish() } } },
            onFrame = { bmp -> analyze(bmp) })
        resetAll()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() = camera.start()

    private fun resetAll() {
        steps = listOf(Step.BLINK, Step.TURN).shuffled() + Step.CAPTURE
        stepIndex = 0
        blinkPhase = 0
        turned = false
        lockedId = null
        samples.clear()
        startedAt = System.currentTimeMillis()
        finished = false
        updateUi("Place your face inside the oval")
    }

    private fun nextStep() {
        stepIndex++
        blinkPhase = 0
        turned = false
    }

    private fun updateUi(message: String) {
        val labels = steps.mapIndexed { i, s ->
            val name = when (s) { Step.BLINK -> "Blink"; Step.TURN -> "Turn head"; Step.CAPTURE -> "Capture" }
            if (i < stepIndex) "✓ $name" else name
        }
        val pct = ((stepIndex.toFloat() + if (steps.getOrNull(stepIndex) == Step.CAPTURE) samples.size.toFloat() / needed else 0f) / steps.size * 100).toInt()
        runOnUiThread {
            instruction.text = message
            stepsText.text = labels.joinToString("   ·   ")
            progressBar.setProgressCompat(pct, true)
        }
    }

    /** Runs on the camera thread for every frame. */
    private fun analyze(bmp: Bitmap) {
        if (finished) return
        if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
            finished = true
            runOnUiThread {
                instruction.text = "Time out. Make sure your face is well lit and try again."
                btnRetry.visibility = View.VISIBLE
            }
            return
        }
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
        if (faces.isEmpty()) { updateUi("Place your face inside the oval"); return }
        if (faces.size > 1) { resetProgress("Only ONE face should be visible"); return }
        val face = faces[0]

        // Liveness must be done by the same face from start to end
        val id = face.trackingId
        if (id != null) {
            if (lockedId == null) lockedId = id
            else if (id != lockedId) { resetProgress("A different face was detected. Starting again."); lockedId = id; return }
        }
        if (face.boundingBox.width() < bmp.width * 0.28f) { updateUi("Move the phone a little closer"); return }

        val left = face.leftEyeOpenProbability ?: -1f
        val right = face.rightEyeOpenProbability ?: -1f
        val yaw = face.headEulerAngleY
        val roll = face.headEulerAngleZ

        when (steps[stepIndex]) {
            Step.BLINK -> {
                if (left < 0 || right < 0) { updateUi("Look at the camera"); return }
                when (blinkPhase) {
                    0 -> if (left > 0.7f && right > 0.7f) blinkPhase = 1
                    1 -> if (left < 0.3f && right < 0.3f) blinkPhase = 2
                    2 -> if (left > 0.6f && right > 0.6f) { nextStep(); updateUi("Good!"); return }
                }
                updateUi("Blink your eyes slowly")
            }
            Step.TURN -> {
                if (abs(yaw) > 25f) turned = true
                if (turned && abs(yaw) < 10f) { nextStep(); updateUi("Good!"); return }
                updateUi(if (!turned) "Turn your head slowly to the LEFT or RIGHT" else "Now look straight at the camera")
            }
            Step.CAPTURE -> {
                val straight = abs(yaw) < 12f && abs(roll) < 12f && left > 0.5f && right > 0.5f
                if (!straight) { updateUi("Look straight at the camera with eyes open"); return }
                val now = System.currentTimeMillis()
                if (now - lastSampleAt > 350) {
                    val emb = embedder?.embed(cropFace(bmp, face.boundingBox)) ?: return
                    samples.add(emb)
                    lastSampleAt = now
                }
                if (samples.size >= needed) {
                    finished = true
                    val arr = JSONArray()
                    samples.forEach { arr.put(it.toJson()) }
                    runOnUiThread {
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_EMBEDDINGS, arr.toString()))
                        finish()
                    }
                } else updateUi("Hold still...")
            }
        }
    }

    private fun resetProgress(message: String) {
        stepIndex = 0
        blinkPhase = 0
        turned = false
        samples.clear()
        updateUi(message)
    }

    private fun cropFace(src: Bitmap, box: Rect): Bitmap {
        val size = (max(box.width(), box.height()) * 1.1f).toInt()
        val left = (box.centerX() - size / 2).coerceIn(0, src.width - 1)
        val top = (box.centerY() - size / 2).coerceIn(0, src.height - 1)
        val w = min(size, src.width - left).coerceAtLeast(1)
        val h = min(size, src.height - top).coerceAtLeast(1)
        return Bitmap.createBitmap(src, left, top, w, h)
    }

    override fun onDestroy() {
        finished = true
        if (::camera.isInitialized) camera.shutdown()
        detector.close()
        embedder?.close()
        super.onDestroy()
    }
}

/** Scans the faculty's live QR code with the camera + ML Kit. Returns the token text. */
class QrScanActivity : BaseActivity() {
    companion object { const val EXTRA_QR = "qr" }

    private lateinit var camera: CameraHelper
    private lateinit var scanner: BarcodeScanner
    @Volatile private var done = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.start() else showMessage("Camera needed", "Allow camera permission to scan the QR code.") { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)
        setupToolbar("Scan classroom QR")
        scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val hint = findViewById<TextView>(R.id.hintText)
        camera = CameraHelper(this, findViewById(R.id.previewView), preferFront = false,
            onError = { msg -> runOnUiThread { showMessage("Camera", msg) { finish() } } },
            onFrame = { bmp ->
                if (!done) {
                    val codes = Tasks.await(scanner.process(InputImage.fromBitmap(bmp, 0)))
                    val value = codes.firstOrNull { it.rawValue != null }?.rawValue
                    if (value != null) {
                        if (value.startsWith("ATT1.")) {
                            done = true
                            runOnUiThread {
                                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_QR, value))
                                finish()
                            }
                        } else runOnUiThread { hint.text = "This is not a Smart Attendance QR code" }
                    }
                }
            })
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.start()
        else permission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        done = true
        camera.shutdown()
        scanner.close()
        super.onDestroy()
    }
}
