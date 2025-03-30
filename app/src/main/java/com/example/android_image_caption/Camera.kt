package com.example.android_image_caption

// Import các thư viện cần thiết
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Lớp `Camera` chịu trách nhiệm mở camera, hiển thị preview, chụp ảnh và gửi ảnh đi xử lý.
 * @param context Context ứng dụng để truy cập CameraManager.
 * @param textureView TextureView để hiển thị preview camera.
 * @param coroutineScope Scope để thực thi tác vụ bất đồng bộ.
 * @param cameraListener Interface để xử lý ảnh sau khi chụp.
 */
class Camera(
    context: Context,
    private val textureView: TextureView,
    private val coroutineScope: CoroutineScope,
    private val cameraListener: ICamera
) {

    // Biến quản lý Camera
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Luồng xử lý riêng cho Camera
    private val handlerThread = HandlerThread("CameraThread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /**
     * Mở camera sau khi TextureView đã sẵn sàng.
     * Nếu TextureView chưa sẵn sàng, sẽ thử lại sau 500ms.
     */
    @SuppressLint("MissingPermission")
    internal fun openCamera() {
        if (textureView.surfaceTexture == null) {
            Log.e("CameraDebug", "TextureView chưa sẵn sàng, thử lại sau 500ms!")
            textureView.postDelayed({ openCamera() }, 500)
            return
        }

        try {
            // Lấy ID của camera sau (back camera)
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList[0]

            Log.d("CameraDebug", "Đang mở Camera ID: $cameraId")

            // Mở camera với ID đã lấy được
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d("CameraDebug", "Camera đã mở!")
                    cameraDevice = camera
                    startPreview() // Bắt đầu hiển thị preview
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.e("CameraDebug", "Camera bị ngắt kết nối!")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("CameraDebug", "Lỗi Camera: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, handler)

        } catch (e: Exception) {
            Log.e("CameraDebug", "Lỗi mở camera: ${e.message}")
        }
    }

    /**
     * Bắt đầu hiển thị preview camera trên TextureView.
     * Nếu TextureView chưa sẵn sàng, thử lại sau 500ms.
     */
    @Suppress("DEPRECATION")
    private fun startPreview() {
        if (!textureView.isAvailable || textureView.surfaceTexture == null) {
            Log.e("CameraDebug", "SurfaceTexture NULL, thử lại sau 500ms!")
            textureView.postDelayed({ startPreview() }, 500)
            return
        }

        val texture = textureView.surfaceTexture!!
        texture.setDefaultBufferSize(1080, 1920) // Đặt kích thước buffer mặc định
        val surface = Surface(texture)

        try {
            // Tạo CaptureRequest để hiển thị preview
            val captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                addTarget(surface) // Thêm Surface của TextureView làm mục tiêu hiển thị
            }

            // Tạo CaptureSession để bắt đầu preview
            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraCaptureSession = session
                    captureRequestBuilder?.let {
                        cameraCaptureSession?.setRepeatingRequest(it.build(), null, handler)
                    }
                    Log.d("CameraDebug", "Camera Preview bắt đầu!")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("CameraDebug", "Cấu hình camera thất bại!")
                }
            }, handler)

        } catch (e: Exception) {
            Log.e("CameraDebug", "Lỗi khi bắt đầu preview: ${e.message}")
        }
    }

    /**
     * Đóng camera và giải phóng tài nguyên.
     */
    internal fun closeCamera() {
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraDevice?.close()
        cameraDevice = null
        Log.d("CameraDebug", "Camera đã được đóng!")
    }

    /**
     * Chụp ảnh từ TextureView và xử lý (upload hoặc offline).
     */
    internal fun captureImage() {
        val bitmap = textureView.bitmap // Lấy ảnh từ TextureView
        if (bitmap == null) return

        coroutineScope.launch {
            if (cameraListener.hasInternetConnection()) { // Kiểm tra mạng
                // Nếu có mạng, tải ảnh lên server
                val imageByteArray = bitmapToByteArray(bitmap)
                cameraListener.uploadImageToServer(imageByteArray)
                Log.d("CameraDebug", "Tải ảnh lên server...")
            } else {
                // Nếu không có mạng, xử lý ảnh offline
                cameraListener.processImageOffline(bitmap)
                Log.d("CameraDebug", "Xử lý ảnh offline...")
            }
        }
    }

    /**
     * Chuyển đổi ảnh Bitmap thành mảng byte để gửi lên server.
     */
    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream) // Nén ảnh với chất lượng 100%
        return outputStream.toByteArray()
    }
}