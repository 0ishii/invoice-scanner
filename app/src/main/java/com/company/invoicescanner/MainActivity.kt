package com.company.invoicescanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.Normalizer
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

    data class ExtractedInvoice(
        val orderNumber: String = "",
        val deliveryNumber: String = "",
        val routeNumber: String = ""
    ) {
        fun hasAny() = orderNumber.isNotEmpty() || deliveryNumber.isNotEmpty() || routeNumber.isNotEmpty()
        fun isComplete() = orderNumber.isNotEmpty() && deliveryNumber.isNotEmpty() && routeNumber.isNotEmpty()
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
     * Đọc metadata EXIF để xác định góc xoay thực tế của cảm biến Camera
     */
    private fun getExifRotation(file: File): Int {
        return try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Xoay Bitmap về hướng chuẩn thẳng đứng (Upright)
     */
    private fun loadUprightBitmap(file: File): Bitmap {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        val rotation = getExifRotation(file)
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Bỏ dấu tiếng Việt để so khớp nhãn chính xác tuyệt đối
     */
    private fun removeVietnameseAccents(str: String): String {
        val nfd = Normalizer.normalize(str, Normalizer.Form.NFD)
        val pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
        return pattern.matcher(nfd).replaceAll("").replace('đ', 'd').replace('Đ', 'D')
    }

    /**
     * Chuẩn hóa lỗi ký tự thường gặp trong OCR in kim / chụp màn hình:
     * Chữ 'O', 'o', 'D', 'Q' nhầm thành số '0'
     * Chữ 'I', 'l', '|' nhầm thành số '1'
     * Chữ 'B' nhầm thành số '8'
     */
    private fun cleanOcrDigits(raw: String): String {
        return raw.replace('O', '0')
            .replace('o', '0')
            .replace('D', '0')
            .replace('Q', '0')
            .replace('I', '1')
            .replace('l', '1')
            .replace('|', '1')
            .replace('B', '8')
            .replace('Z', '2')
            .replace('S', '5')
            .replace('s', '5')
    }

    /**
     * Bóc tách thông tin hóa đơn từ dữ liệu Text Blocks của ML Kit
     * Hỗ trợ layout 2 cột (Nhãn bên trái, Số bên phải) thông qua tọa độ không gian Y
     */
    private fun parseInvoiceFromVisionText(visionText: Text): ExtractedInvoice {
        var orderNumber = ""
        var deliveryNumber = ""
        var routeNumber = ""

        val allLines = visionText.textBlocks.flatMap { it.lines }

        // Helper để tìm các text cùng hàng ngang (Y-axis tolerance) sang bên phải
        fun findHorizontallyAlignedLines(targetLine: Text.Line): List<Text.Line> {
            val targetBox = targetLine.boundingBox ?: return emptyList()
            val targetCenterY = targetBox.centerY()
            val targetHeight = targetBox.height().coerceAtLeast(10)

            return allLines.filter { other ->
                if (other == targetLine) return@filter false
                val otherBox = other.boundingBox ?: return@filter false
                val otherCenterY = otherBox.centerY()
                // Nằm cùng dải tung độ và ở bên phải (hoặc ngay sát)
                Math.abs(otherCenterY - targetCenterY) <= targetHeight * 1.5 && otherBox.left >= targetBox.left - 50
            }.sortedBy { it.boundingBox?.left ?: 0 }
        }

        // 1. Quét theo vị trí tương đối và nhãn dòng
        for (line in allLines) {
            val rawText = line.text.trim()
            val cleanLabel = removeVietnameseAccents(rawText).lowercase()
            val alignedTexts = findHorizontallyAlignedLines(line).map { it.text }
            val combinedRow = (listOf(rawText) + alignedTexts).joinToString(" ")
            val normalizedRowDigits = cleanOcrDigits(combinedRow)

            // Dò Số Đơn Đặt Hàng (SO - Sales Order: 200xxxxxxx)
            if (cleanLabel.contains("so don") || cleanLabel.contains("don dat") || cleanLabel.contains("don dai") || cleanLabel.contains("dat hang")) {
                val m = Pattern.compile("\\b(200\\d{7}|\\d{10})\\b").matcher(normalizedRowDigits)
                if (m.find() && orderNumber.isEmpty()) {
                    orderNumber = m.group(1) ?: ""
                }
            }

            // Dò Số Lệnh Giao Hàng (DO - Delivery Order: 810xxxxxxx, tránh nhầm chữ "phieu xuat kho")
            if ((cleanLabel.contains("so lenh") || cleanLabel.contains("lenh giao") || cleanLabel.contains("giao hang"))
                && !cleanLabel.contains("phieu") && !cleanLabel.contains("kiem")) {
                val m = Pattern.compile("\\b(810\\d{7}|\\d{10})\\b").matcher(normalizedRowDigits)
                if (m.find() && deliveryNumber.isEmpty()) {
                    val cand = m.group(1) ?: ""
                    if (cand != orderNumber) deliveryNumber = cand
                }
            }

            // Dò Tuyến Đường (1115xx hoặc số 4-8 chữ số cạnh nhãn "tuyen")
            if (cleanLabel.contains("tuyen") || cleanLabel.contains("tuyen duong") || cleanLabel.contains("route")) {
                val mRoute = Pattern.compile("\\b(1115\\d{2}|\\d{4,8})\\b").matcher(normalizedRowDigits)
                if (mRoute.find() && routeNumber.isEmpty()) {
                    routeNumber = mRoute.group(1) ?: ""
                }
            }
        }

        // 2. Global Regex Scan trên toàn bộ nội dung ảnh (bất kể nhãn có bị cắt hay không)
        val fullText = visionText.text
        val normalizedFullDigits = cleanOcrDigits(fullText)

        // Quét Sales Order (200xxxxxxx)
        if (orderNumber.isEmpty()) {
            val m = Pattern.compile("\\b(200\\d{7})\\b").matcher(normalizedFullDigits)
            if (m.find()) {
                orderNumber = m.group(1) ?: ""
            }
        }

        // Quét Delivery Order (810xxxxxxx)
        if (deliveryNumber.isEmpty()) {
            val m = Pattern.compile("\\b(810\\d{7})\\b").matcher(normalizedFullDigits)
            if (m.find()) {
                val cand = m.group(1) ?: ""
                if (cand != orderNumber) deliveryNumber = cand
            }
        }

        // Quét Tuyến Đường (1115xx)
        if (routeNumber.isEmpty()) {
            val m = Pattern.compile("\\b(1115\\d{2})\\b").matcher(normalizedFullDigits)
            if (m.find()) {
                routeNumber = m.group(1) ?: ""
            }
        }

        return ExtractedInvoice(orderNumber, deliveryNumber, routeNumber)
    }

    /**
     * Nhận diện Offline On-Device bằng Google ML Kit
     */
    private fun processImageOnDevice(file: File) {
        val uprightBitmap = try {
            loadUprightBitmap(file)
        } catch (e: Exception) {
            BitmapFactory.decodeFile(file.absolutePath)
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(uprightBitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val result = parseInvoiceFromVisionText(visionText)

                // Nếu ảnh chưa nhận diện được gì (có thể do chụp ngang hoặc ngược), thử tự động xoay góc để nhận diện
                if (!result.hasAny()) {
                    tryRotateAndRetry(uprightBitmap, recognizer, 90f) { retryResult ->
                        displayResult(retryResult)
                    }
                } else {
                    displayResult(result)
                }
            }
            .addOnFailureListener { e ->
                progressBar.visibility = View.GONE
                btnCapture.isEnabled = true
                Toast.makeText(this, "Nhận diện thất bại: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Tự động xoay 90, 180, 270 độ thử lại nếu chưa tìm thấy dữ liệu (Self-Correction Fallback)
     */
    private fun tryRotateAndRetry(
        originalBitmap: Bitmap,
        recognizer: TextRecognizer,
        angle: Float,
        onComplete: (ExtractedInvoice) -> Unit
    ) {
        val rotated = rotateBitmap(originalBitmap, angle)
        val image = InputImage.fromBitmap(rotated, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val res = parseInvoiceFromVisionText(visionText)
                if (res.hasAny() || angle >= 270f) {
                    onComplete(res)
                } else {
                    tryRotateAndRetry(originalBitmap, recognizer, angle + 90f, onComplete)
                }
            }
            .addOnFailureListener {
                onComplete(ExtractedInvoice())
            }
    }

    private fun displayResult(result: ExtractedInvoice) {
        progressBar.visibility = View.GONE
        btnCapture.isEnabled = true

        tvOrderNumber.text = if (result.orderNumber.isNotEmpty()) result.orderNumber else "Không tìm thấy"
        tvDeliveryNumber.text = if (result.deliveryNumber.isNotEmpty()) result.deliveryNumber else "Không tìm thấy"
        tvRoute.text = if (result.routeNumber.isNotEmpty()) result.routeNumber else "Không tìm thấy"

        if (result.hasAny()) {
            Toast.makeText(this, "Đã nhận diện thành công!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Không tìm thấy thông tin trên hóa đơn. Vui lòng căn chỉnh lại!", Toast.LENGTH_LONG).show()
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
