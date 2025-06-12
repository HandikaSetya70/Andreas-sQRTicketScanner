// MainActivity.kt - MINIMAL VERSION (No problematic dependencies)
package com.dicoding.qrticketscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dicoding.qrticketscanner.data.API.ApiClient
import com.dicoding.qrticketscanner.data.API.TicketApiService
import com.dicoding.qrticketscanner.data.ScannerInfo
import com.dicoding.qrticketscanner.data.TicketInfo
import com.dicoding.qrticketscanner.data.ValidationRequest
import com.dicoding.qrticketscanner.data.ValidationResponse
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.dicoding.qrticketscanner.databinding.ActivityMainBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var apiService: TicketApiService

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isScanning = true
    private var lastScanTime = 0L
    private val scanCooldown = 3000L

    // Audio feedback
    private lateinit var toneGenerator: ToneGenerator
    private lateinit var vibrator: Vibrator

    companion object {
        private const val TAG = "TicketScanner"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize components
        initializeComponents()

        // Set up UI
        setupUI()

        // Set up surface view
        binding.surfaceView.holder.addCallback(this)

        // Check camera permission
        checkCameraPermission()
    }

    private fun initializeComponents() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Initialize ML Kit barcode scanner
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        // Initialize audio feedback
        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        // Initialize API service
        apiService = ApiClient.create()
    }

    private fun setupUI() {
        binding.resetButton.setOnClickListener {
            resetScanner()
        }

        // Set initial status
        updateStatus(ScanStatus.READY)
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
                Log.d(TAG, "Camera permission already granted")
            }
            else -> {
                // Request permission using old method (no ActivityResultContracts)
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    // Handle permission result (old method)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Camera permission granted")
                    // Permission granted, camera will start when surface is ready
                } else {
                    Log.e(TAG, "Camera permission denied")
                    showError("Camera permission is required for QR scanning")
                }
            }
        }
    }

    // SurfaceHolder.Callback methods
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: ${width}x${height}")
        // Start camera when surface is ready and we have permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
        closeCamera()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    private fun startCamera() {
        if (backgroundThread == null) {
            startBackgroundThread()
        }

        try {
            val cameraId = getCameraId()
            if (cameraId == null) {
                showError("No suitable camera found")
                return
            }

            Log.d(TAG, "Opening camera: $cameraId")

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted")
                return
            }

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting camera", e)
            showError("Error starting camera: ${e.message}")
        }
    }

    private fun getCameraId(): String? {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return cameraId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera ID", e)
        }
        return null
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "Camera opened")
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.d(TAG, "Camera disconnected")
            cameraDevice?.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            cameraDevice?.close()
            cameraDevice = null
            runOnUiThread {
                showError("Camera error occurred")
            }
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val surface = binding.surfaceView.holder.surface

            // Set up ImageReader for QR scanning
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 1)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    processImageForQR(image)
                    image.close()
                }
            }, backgroundHandler)

            val surfaces = listOf(surface, imageReader!!.surface)

            cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "Capture session configured")
                    captureSession = session
                    updatePreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                    runOnUiThread {
                        showError("Camera session configuration failed")
                    }
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera preview session", e)
            runOnUiThread {
                showError("Error creating camera session: ${e.message}")
            }
        }
    }

    private fun updatePreview() {
        if (cameraDevice == null) return

        try {
            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(binding.surfaceView.holder.surface)
            captureRequestBuilder.addTarget(imageReader!!.surface)

            // Set auto-focus
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            captureSession?.setRepeatingRequest(
                captureRequestBuilder.build(),
                null,
                backgroundHandler
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error updating preview", e)
        }
    }

    private fun processImageForQR(image: Image) {
        if (!isScanning) return

        try {
            val inputImage = InputImage.fromMediaImage(image, 0)

            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        barcode.rawValue?.let { qrContent ->
                            runOnUiThread {
                                handleQRCodeDetected(qrContent)
                            }
                            return@addOnSuccessListener
                        }
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "QR scanning failed", exception)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image for QR", e)
        }
    }

    private fun handleQRCodeDetected(qrContent: String) {
        // Prevent rapid scanning
        val currentTime = System.currentTimeMillis()
        if (!isScanning || currentTime - lastScanTime < scanCooldown) {
            return
        }

        lastScanTime = currentTime
        isScanning = false

        Log.d(TAG, "QR Code detected: $qrContent")

        // Update UI to show scanning
        updateStatus(ScanStatus.SCANNING)
        playFeedback(FeedbackType.SCAN)

        // Validate ticket via API
        validateTicket(qrContent)
    }

    private fun validateTicket(qrContent: String) {
        val validationRequest = ValidationRequest(
            qr_data = qrContent,
            scanner_info = ScannerInfo(
                admin_id = "admin-scanner-001",
                location = "Main Gate",
                device_id = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ANDROID_ID
                )
            )
        )

        apiService.validateTicket(validationRequest).enqueue(object : Callback<ValidationResponse> {
            override fun onResponse(call: Call<ValidationResponse>, response: Response<ValidationResponse>) {
                if (response.isSuccessful) {
                    response.body()?.let { validationResponse ->
                        handleValidationResponse(validationResponse)
                    }
                } else {
                    showValidationError("Validation failed: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<ValidationResponse>, t: Throwable) {
                Log.e(TAG, "API call failed", t)
                showValidationError("Network error: ${t.message}")
            }
        })
    }

    private fun handleValidationResponse(response: ValidationResponse) {
        when (response.validation_result) {
            "valid" -> {
                updateStatus(ScanStatus.VALID)
                showTicketInfo(response.ticket_info)
                playFeedback(FeedbackType.SUCCESS)
            }
            "invalid" -> {
                updateStatus(ScanStatus.INVALID)
                showError("Invalid ticket: ${response.message}")
                playFeedback(FeedbackType.ERROR)
            }
            "revoked" -> {
                updateStatus(ScanStatus.REVOKED)
                showError("Ticket revoked: ${response.message}")
                playFeedback(FeedbackType.ERROR)
            }
            else -> {
                updateStatus(ScanStatus.ERROR)
                showError("Unknown validation result")
                playFeedback(FeedbackType.ERROR)
            }
        }

        // Auto-reset after 5 seconds
        binding.root.postDelayed({
            resetScanner()
        }, 5000)
    }

    private fun showValidationError(message: String) {
        updateStatus(ScanStatus.ERROR)
        showError(message)
        playFeedback(FeedbackType.ERROR)

        // Auto-reset after 3 seconds
        binding.root.postDelayed({
            resetScanner()
        }, 3000)
    }

    private fun updateStatus(status: ScanStatus) {
        when (status) {
            ScanStatus.READY -> {
                binding.statusIndicator.setBackgroundResource(R.drawable.status_neutral)
                binding.statusText.text = "Ready to scan"
                binding.detailsText.text = "Point camera at QR code"
                binding.instructionText.text = "Position QR code within the frame"
                binding.resetButton.visibility = View.GONE
                binding.ticketInfoContainer.visibility = View.GONE
            }
            ScanStatus.SCANNING -> {
                binding.statusIndicator.setBackgroundResource(R.drawable.status_scanning)
                binding.statusText.text = "Validating..."
                binding.detailsText.text = "Please wait"
                binding.instructionText.text = "Checking ticket validity"
            }
            ScanStatus.VALID -> {
                binding.statusIndicator.setBackgroundResource(R.drawable.status_valid)
                binding.statusText.text = "✅ VALID TICKET"
                binding.detailsText.text = "Allow entry"
                binding.instructionText.text = "Entry approved"
                binding.resetButton.visibility = View.VISIBLE
            }
            ScanStatus.INVALID -> {
                binding.statusIndicator.setBackgroundResource(R.drawable.status_invalid)
                binding.statusText.text = "❌ INVALID TICKET"
                binding.detailsText.text = "Deny entry"
                binding.instructionText.text = "Ticket not valid"
                binding.resetButton.visibility = View.VISIBLE
            }
            ScanStatus.REVOKED -> {
                binding.statusIndicator.setBackgroundResource(R.drawable.status_revoked)
                binding.statusText.text = "🚫 REVOKED TICKET"
                binding.detailsText.text = "Deny entry"
                binding.instructionText.text = "Ticket has been revoked"
                binding.resetButton.visibility = View.VISIBLE
            }
            ScanStatus.ERROR -> {
                binding.statusIndicator.setBackgroundResource(R.drawable.status_error)
                binding.statusText.text = "⚠️ ERROR"
                binding.detailsText.text = "Try again"
                binding.instructionText.text = "Validation failed"
                binding.resetButton.visibility = View.VISIBLE
            }
        }
    }

    private fun showTicketInfo(ticketInfo: TicketInfo?) {
        ticketInfo?.let { info ->
            binding.ticketInfoContainer.visibility = View.VISIBLE
            binding.ticketNumber.text = "Ticket #${info.ticket_number}"
            binding.eventName.text = "Event: ${info.event_name}"
            binding.holderName.text = "Holder: ${info.holder_name}"
        }
    }

    private fun showError(message: String) {
        binding.detailsText.text = message
        Log.e(TAG, message)
    }

    private fun playFeedback(type: FeedbackType) {
        when (type) {
            FeedbackType.SCAN -> {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                vibrate(100)
            }
            FeedbackType.SUCCESS -> {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 200)
                vibrate(200)
            }
            FeedbackType.ERROR -> {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 300)
                vibrate(300)
            }
        }
    }

    private fun vibrate(duration: Long) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    private fun resetScanner() {
        isScanning = true
        updateStatus(ScanStatus.READY)
        binding.ticketInfoContainer.visibility = View.GONE
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null

        imageReader?.close()
        imageReader = null
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator.release()
    }

    enum class ScanStatus {
        READY, SCANNING, VALID, INVALID, REVOKED, ERROR
    }

    enum class FeedbackType {
        SCAN, SUCCESS, ERROR
    }
}