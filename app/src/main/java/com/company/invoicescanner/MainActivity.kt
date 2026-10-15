package com.company.invoicescanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var btnCapture: FloatingActionButton
    private lateinit var progressBar: ProgressBar

    private lateinit var tvOrderNumber: TextView
    private lateinit var tvDeliveryNumber: TextView
    private lateinit var tvRoute: TextView

    private lateinit var btnCopyOrder: Button
    private lateinit var btnCopyDelivery: Button
    private lateinit var btnCopyRoute: Button

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Ánh xạ View
        viewFinder = findViewById(R.id.viewFinder)
        btnCapture = findViewById(R.id.btnCapture)
        progressBar = findViewById(R.id.progressBar)

        tvOrderNumber = findViewById(R.id.tvOrderNumber)
        tvDeliveryNumber = findViewById(R.id.tvDeliveryNumber)
        tvRoute = findViewById(R.id.tvRoute)

        btnCopyOrder = findViewById(R.id.btnCopyOrder)
        btnCopyDelivery = findViewById(R.id.btnCopyDelivery)
        btnCopyRoute = findViewById(R.id.btnCopyRoute)

        // Cài đặt sự kiện Copy
        btnCopyOrder.setOnClickListener { copyToClipboard("Số Đơn Đặt Hàng", tvOrderNumber.text.toString()) }
        btnCopyDelivery.setOnClickListener { copyToClipboard("Số Lệnh Giao Hàng", tvDeliveryNumber.text.toString()) }
        btnCopyRoute.setOnClickListener { copyToClipboard("Tuyến Đường", tvRoute.text.toString()) }

        btnCapture.setOnClickListener { takePhotoAndProcess() }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Kiểm tra quyền Camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Toast.makeText(this, "Không thể khởi động Camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoAndProcess() {
        val imageCapture = imageCapture ?: return
        progressBar.visibility = View.VISIBLE
        btnCapture.isEnabled = false

        val photoFile = File(
            externalCacheDir,
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    progressBar.visibility = View.GONE
                    btnCapture.isEnabled = true
                    Toast.makeText(baseContext, "Lỗi chụp ảnh: ${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    processImageOnDevice(photoFile)
                }
            }
        )
    }

    /**
     * Nhận diện Offline On-Device bằng Google ML Kit
     */
    private fun processImageOnDevice(file: File) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                progressBar.visibility = View.GONE
                btnCapture.isEnabled = true

                var orderNumber = ""
                var deliveryNumber = ""
                var routeNumber = ""

                val fullText = visionText.text
                val lines = visionText.textBlocks.flatMap { it.lines }

                // 1. Phân tích từng dòng chữ
                for (line in lines) {
                    val txt = line.text.trim()
                    val lower = txt.lowercase()

                    // Dò Số Đơn Đặt Hàng (Ưu tiên bắt đầu bằng 200...)
                    if (lower.contains("so don") || lower.contains("don dat") || lower.contains("don dai")) {
                        val m = Pattern.compile("\\b(200\\d{7}|\\d{10})\\b").matcher(txt)
                        if (m.find()) orderNumber = m.group(1) ?: ""
                    }

                    // Dò Số Lệnh Giao Hàng (Ưu tiên bắt đầu bằng 810..., tránh nhầm tiêu đề phiếu)
                    if ((lower.contains("so lenh") || lower.contains("lenh giao")) && !lower.contains("phieu")) {
                        val m = Pattern.compile("\\b(810\\d{7}|\\d{10})\\b").matcher(txt)
                        if (m.find()) {
                            val candidate = m.group(1) ?: ""
                            if (candidate != orderNumber) deliveryNumber = candidate
                        }
                    }

                    // Dò Tuyến Đường
                    if (lower.contains("tuyen") || lower.contains("tuyen duong")) {
                        val m = Pattern.compile("\\b(\\d{4,8})\\b").matcher(txt)
                        if (m.find()) routeNumber = m.group(1) ?: ""
                    }
                }

                // 2. Fallback regex quét toàn màn hình nếu camera dí quá sát làm đứt dòng nhãn
                if (orderNumber.isEmpty()) {
                    val m = Pattern.compile("\\b(200\\d{7})\\b").matcher(fullText)
                    if (m.find()) orderNumber = m.group(1) ?: ""
                }
                if (deliveryNumber.isEmpty()) {
                    val m = Pattern.compile("\\b(810\\d{7})\\b").matcher(fullText)
                    if (m.find()) {
                        val candidate = m.group(1) ?: ""
                        if (candidate != orderNumber) deliveryNumber = candidate
                    }
                }

                // Cập nhật giao diện
                tvOrderNumber.text = if (orderNumber.isNotEmpty()) orderNumber else "Không tìm thấy"
                tvDeliveryNumber.text = if (deliveryNumber.isNotEmpty()) deliveryNumber else "Không tìm thấy"
                tvRoute.text = if (routeNumber.isNotEmpty()) routeNumber else "Không tìm thấy"

                Toast.makeText(this, "Đã nhận diện thành công!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                progressBar.visibility = View.GONE
                btnCapture.isEnabled = true
                Toast.makeText(this, "Nhận diện thất bại: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Sao chép vào bộ nhớ đệm (Clipboard) và hiển thị Toast thông báo
     */
    private fun copyToClipboard(label: String, text: String) {
        if (text.isEmpty() || text == "Chưa quét" || text == "Không tìm thấy") {
            Toast.makeText(this, "Không có dữ liệu để sao chép!", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, "Đã chép $label: $text", Toast.LENGTH_SHORT).show()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Bạn cần cấp quyền Camera để quét hóa đơn.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
