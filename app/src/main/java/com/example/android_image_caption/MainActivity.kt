package com.example.android_image_caption

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.TextureView
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.Locale
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.ProgressBar

class MainActivity : AppCompatActivity(), ICamera, IModel, TextToSpeech.OnInitListener {

    private lateinit var textureView: TextureView
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var languageSwitch: Switch
    private lateinit var textToSpeech: TextToSpeech

    private lateinit var progressSpinner: ProgressBar
    private lateinit var linearProgressBar: ProgressBar

    private lateinit var camera: Camera
    private lateinit var offlineModel: OfflineModel
    private lateinit var onlineModel: OnlineModel

    override val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override var selectedLanguage: String = "en"


    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        languageSwitch = findViewById(R.id.languageSwitch)
        textToSpeech = TextToSpeech(this, this)
        progressSpinner = findViewById(R.id.progressSpinner)
        linearProgressBar = findViewById(R.id.linearProgressBar)

        offlineModel = OfflineModel(this, this)
        onlineModel = OnlineModel(this)

        selectedLanguage = getSavedLanguage()
        languageSwitch.isChecked = (selectedLanguage == "vi")

        // Nếu chưa có quyền -> Yêu cầu quyền camera
        if (!hasPermissions()) {
            requestPermissions()
        } else {
            // Nếu đã có quyền -> Khởi tạo Camera
            initializeCamera()
        }

        setupListeners()

        loadAllModels()
    }

    private fun setupListeners() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCameraIfReady()
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                camera.closeCamera()
                return false
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        // Chuyển đổi ngôn ngữ
        languageSwitch.setOnCheckedChangeListener { _, isChecked ->
            selectedLanguage = if (isChecked) "vi" else "en"
            saveLanguage(selectedLanguage)
            val langMessage = if (isChecked) "Bạn đã đổi sang Tiếng Việt" else "You have switched to English"
            speakText(langMessage, false)
        }

        // Xử lý chụp ảnh khi nhấn vào TextureView
        textureView.setOnClickListener {
            speakText(
                if (selectedLanguage == "vi") "Chụp ảnh thành công, xin chờ xử lý"
                else "Photo captured successfully, please wait"
                , true)
            camera.captureImage()
        }
    }

    private fun saveLanguage(lang: String) {
        val sharedPref = getSharedPreferences("AppSettings", MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("selected_language", lang)
            apply()
        }
        Log.d("Settings", "Ngôn ngữ đã được lưu: $lang")
    }

    private fun getSavedLanguage(): String {
        val sharedPref = getSharedPreferences("AppSettings", MODE_PRIVATE)
        return sharedPref.getString("selected_language", "en") ?: "en" // Mặc định tiếng Anh
    }

    private fun loadAllModels() {
        runOnUiThread {
            progressSpinner.visibility = View.VISIBLE
            linearProgressBar.visibility = View.VISIBLE
            updateProgress(0)
        }
        offlineModel.loadModel(
            onLoadComplete = {
                runOnUiThread {
                    updateProgress(100)
                    progressSpinner.visibility = View.GONE
                    linearProgressBar.visibility = View.GONE

                    val instruction = if (selectedLanguage == "vi") {
                        "Hệ thống đã sẵn sàng. Nhấn vào màn hình để chụp ảnh. Nhấp vào góc trái trên cùng để đổi ngôn ngữ phù hợp."
                    } else {
                        "The system is ready. Tap the screen to capture an image. Click on the top left corner to change the language."
                    }
                    speakText(instruction, false)
                }
            },
            onProgressUpdate = { progress -> updateProgress(progress) }
        )
    }

    private fun updateProgress(progress: Int) {
        runOnUiThread {
            linearProgressBar.progress = progress
            Log.d("Progress", "Tiến trình tải: $progress%")
        }
    }

    override fun processImageOffline(bitmap: Bitmap) {
        blockUI()
        offlineModel.predict(bitmap, selectedLanguage)
    }

    override fun uploadImageToServer(imageBytes: ByteArray) {
        blockUI()
        onlineModel.uploadImageToServer(imageBytes)
    }

    override fun hasInternetConnection(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    override fun speakText(text: String, isBlockUI: Boolean) {

        blockUI()

        val selectedLocale = if (selectedLanguage == "vi") Locale("vi", "VN") else Locale.US
        textToSpeech.language = selectedLocale

        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "TTS_ID") }

        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d("TTS", "Bắt đầu phát âm...")
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    Log.d("TTS", "Hoàn thành phát âm!")
                    unblockUI()  // Chỉ mở UI sau khi nói xong
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    unblockUI()
                }
            }
        })

        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, "TTS_ID")
    }

    private fun blockUI() {
        runOnUiThread {
            textureView.isEnabled = false
            languageSwitch.isEnabled = false
            progressSpinner.visibility = View.VISIBLE
            Log.d("UI", "UI bị khóa")
        }
    }

    private fun unblockUI() {
        runOnUiThread {
            textureView.isEnabled = true
            languageSwitch.isEnabled = true
            progressSpinner.visibility = View.GONE
            Log.d("UI", "UI được mở lại")
        }
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            showToast("Ứng dụng cần quyền camera để hoạt động!")
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
    }

    private fun initializeCamera() {
        camera = Camera(this, textureView, coroutineScope, this)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCameraIfReady()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                camera.closeCamera()
                return false
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 101) { // Kiểm tra đúng request code
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("Permission", "Quyền camera đã được cấp!")
                initializeCamera() // Khởi tạo camera ngay khi được cấp quyền
            } else {
                Log.e("Permission", "Quyền camera bị từ chối!")
                showToast("Ứng dụng cần quyền camera để hoạt động. Hãy cấp quyền trong cài đặt!")
            }
        }
    }

    private fun openCameraIfReady() {
        if (::camera.isInitialized && textureView.surfaceTexture != null) {
            camera.openCamera()
        } else {
            textureView.postDelayed({ openCameraIfReady() }, 500)
        }
    }

    override fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {

            val vietnameseStatus = textToSpeech.isLanguageAvailable(Locale("vi", "VN"))
            val englishStatus = textToSpeech.isLanguageAvailable(Locale.US)

            val missingLanguages = mutableListOf<String>()

            if (vietnameseStatus == TextToSpeech.LANG_MISSING_DATA || vietnameseStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Thiếu dữ liệu TTS tiếng Việt!")
                missingLanguages.add("Tiếng Việt")
            }

            if (englishStatus == TextToSpeech.LANG_MISSING_DATA || englishStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Thiếu dữ liệu TTS tiếng Anh!")
                missingLanguages.add("Tiếng Anh")
            }

            if (missingLanguages.isNotEmpty()) {
                val missingText = missingLanguages.joinToString(" và ")
                showToast("Thiếu dữ liệu TTS: $missingText! Đang mở cài đặt...")

                val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                startActivity(installIntent)
            } else {
                Log.d("TTS", "TTS đã sẵn sàng cho cả Tiếng Việt và Tiếng Anh!")
            }

            val instruction = if (selectedLanguage == "vi") {
                "Xin hãy chờ 30 giây để hệ thống khởi động."
            } else {
                "Please wait 30 seconds for the system to start."
            }
            speakText(instruction, true)

        } else {
            showToast("Lỗi khởi tạo TTS")
        }
    }
}