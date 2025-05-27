package com.example.scan
import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import com.example.scan.databinding.FragmentFirstBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.example.scan.BarcodeOverlay
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private val isProcessing = AtomicBoolean(false)
    private var lastSuccessfulScanTime: Long = 0
    private val DEDUPLICATION_INTERVAL_MS = 2000 // 2 seconds

    // Use the shared view model
    private val sharedViewModel: SharedViewModel by activityViewModels()

    private lateinit var csvFile: File
    private var csvWriter: CSVWriter? = null
    private val logFileName by lazy { getString(R.string.log_file_name) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupCsvLogger()
        loadScannedHistory() // Load previously scanned barcodes from CSV

        // Observe log clearing events
        sharedViewModel.logCleared.observe(viewLifecycleOwner, Observer { logCleared ->
            if (logCleared) {
                setupCsvLogger() // Recreate CSV logger with empty file
                binding.barcodeResultTextview.text = ""  // Clear the displayed barcode
            }
        })

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            showToast(getString(R.string.permissions_required_toast))
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return (activity as? MainActivity)?.allPermissionsGranted() ?: false
    }

    private fun loadScannedHistory() {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, logFileName)
        if (!file.exists()) return // No history to load

        val scannedCodes = mutableSetOf<String>()
        try {
            CSVReader(FileReader(file)).use { csvReader ->
                csvReader.readNext() // Skip header
                var line: Array<String>?
                while (csvReader.readNext().also { line = it } != null) {
                    if (line != null && line!!.isNotEmpty()) {
                        scannedCodes.add(line!![0]) // Add barcode to history
                    }
                }
            }
            // Initialize the shared view model with loaded barcodes
            sharedViewModel.initializeScannedSet(scannedCodes)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading scanned history from CSV: ${e.message}", e)
        }
    }

    private fun setupCsvLogger() {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                if (!downloadsDir.mkdirs()) {
                    Log.e(TAG, "Failed to create Downloads directory.")
                    showToast(getString(R.string.error_creating_csv_directory))
                    return
                }
            }
            csvFile = File(downloadsDir, logFileName)
            val fileExists = csvFile.exists()
            val fileWriter = FileWriter(csvFile, true)
            csvWriter = CSVWriter(fileWriter)

            if (!fileExists || csvFile.length() == 0L) {
                csvWriter?.writeNext(arrayOf(getString(R.string.csv_header_barcode), getString(R.string.csv_header_timestamp)))
                csvWriter?.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error setting up CSV logger: ${e.message}", e)
            showToast(getString(R.string.error_creating_csv))
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception with CSV logger: ${e.message}", e)
            showToast(getString(R.string.error_storage_permission_csv))
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
                showToast(getString(R.string.error_camera_init_failed))
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val currentCameraProvider = cameraProvider
            ?: run {
                Log.e(TAG, "Camera initialization failed: Provider is null.")
                showToast(getString(R.string.error_camera_provider_null))
                return
            }

        // ensure preview scales uniformly within view to match overlay mapping
        binding.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, BarcodeAnalyzer(binding.barcodeOverlay) { barcodes ->
                    if (isProcessing.compareAndSet(false, true)) {
                        processBarcodes(barcodes)
                    }
                })
            }

        try {
            currentCameraProvider.unbindAll()
            camera = currentCameraProvider.bindToLifecycle(
                viewLifecycleOwner, cameraSelector, preview, imageAnalysis
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            showToast(getString(R.string.error_camera_binding_failed))
        }
    }

    private fun processBarcodes(barcodes: List<Barcode>) {
        try {
            if (barcodes.isNotEmpty()) {
                val currentTime = System.currentTimeMillis()
                val barcode = barcodes.first()
                val barcodeValue = barcode.rawValue ?: getString(R.string.barcode_value_na)

                if (sharedViewModel.hasBarcodeBeenScanned(barcodeValue)) {
                    Log.d(TAG, "Barcode $barcodeValue already scanned and logged.")
                    if (binding.barcodeResultTextview.text.toString().contains(barcodeValue) == false) {
                        showToast(getString(R.string.barcode_already_scanned_toast))
                        updateUiWithBarcode(barcodeValue, "Already Logged")
                    }
                } else if (currentTime - lastSuccessfulScanTime > DEDUPLICATION_INTERVAL_MS) {
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    Log.d(TAG, "New barcode detected: $barcodeValue at $timestamp")
                    updateUiWithBarcode(barcodeValue, timestamp)
                    logToCsv(barcodeValue, timestamp)
                    sharedViewModel.addBarcodeToScannedSet(barcodeValue)
                    lastSuccessfulScanTime = currentTime
                    showScanStatus(getString(R.string.scan_success), true)
                } else {
                    Log.d(TAG, "Rapid duplicate barcode ignored for logging: $barcodeValue")
                    updateUiWithBarcode(barcodeValue, "Scanning...")
                }
            }
        } finally {
            isProcessing.set(false)
        }
    }

    private fun updateUiWithBarcode(barcodeValue: String, timestamp: String) {
        activity?.runOnUiThread {
            binding.barcodeResultTextview.text = getString(R.string.barcode_display_format, barcodeValue, timestamp)
        }
    }

    private fun logToCsv(barcode: String, timestamp: String) {
        try {
            csvWriter?.writeNext(arrayOf(barcode, timestamp))
            csvWriter?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error writing to CSV: ${e.message}", e)
            showToast(getString(R.string.error_writing_csv))
        }
    }

    private fun showScanStatus(message: String, isSuccess: Boolean) {
        activity?.runOnUiThread {
            binding.scanStatusTextview.text = message
            binding.scanStatusTextview.setBackgroundColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isSuccess) android.R.color.holo_green_light else android.R.color.holo_red_light
                )
            )
            binding.scanStatusTextview.visibility = View.VISIBLE
            binding.scanStatusTextview.postDelayed({
                binding.scanStatusTextview.visibility = View.GONE
            }, 2000)
        }
    }

    private class BarcodeAnalyzer(
        private val overlay: BarcodeOverlay,
        private val listener: (barcodes: List<Barcode>) -> Unit
    ) : ImageAnalysis.Analyzer {
        private val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
        )
        private var lastAnalyzedTimestamp = 0L
        private val THROTTLE_INTERVAL_MS = 100

        @SuppressLint("UnsafeExperimentalUsageError", "UnsafeOptInUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastAnalyzedTimestamp < THROTTLE_INTERVAL_MS) {
                overlay.post {
                    overlay.barcodes = emptyList()
                    overlay.invalidate()
                }
                imageProxy.close()
                return
            }

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(inputImage)
                    .addOnSuccessListener { detectedBarcodes ->
                        listener(detectedBarcodes)
                        overlay.post {
                            overlay.barcodes = detectedBarcodes
                            // account for rotation: swap dims if rotated 90 or 270
                            val rot = imageProxy.imageInfo.rotationDegrees
                            if (rot % 180 == 0) {
                                overlay.imageWidth = inputImage.width
                                overlay.imageHeight = inputImage.height
                            } else {
                                overlay.imageWidth = inputImage.height
                                overlay.imageHeight = inputImage.width
                            }
                            overlay.invalidate()
                        }
                    }
                    .addOnFailureListener { e ->
                        overlay.post {
                            overlay.barcodes = emptyList()
                            overlay.invalidate()
                        }
                        Log.e(TAG, "Barcode scanning failed", e)
                        listener(emptyList())
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                        lastAnalyzedTimestamp = currentTime
                    }
            } else {
                overlay.post {
                    overlay.barcodes = emptyList()
                    overlay.invalidate()
                }
                imageProxy.close()
            }
        }
    }

    private fun showToast(message: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        isProcessing.set(false)
        if (allPermissionsGranted()) {
            if (cameraProvider == null) {
                startCamera()
            } else {
                bindCameraUseCases()
            }
        } else {
            showToast(getString(R.string.permissions_required_to_scan_toast))
        }
    }

    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll()
        isProcessing.set(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        try {
            csvWriter?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing CSV writer: ${e.message}", e)
        }
        _binding = null
    }

    companion object {
        private const val TAG = "BarcodeScannerFragment"
    }
}

