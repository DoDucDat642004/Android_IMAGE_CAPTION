package com.example.android_image_caption

// Import các thư viện cần thiết
import kotlinx.coroutines.* // Dùng để xử lý coroutine trong Kotlin
import okhttp3.* // Thư viện OkHttp để thực hiện HTTP requests
import okhttp3.MediaType.Companion.toMediaTypeOrNull // Xác định loại media cho request
import okhttp3.RequestBody.Companion.toRequestBody // Tạo request body từ byte array
import org.json.JSONObject // Dùng để xử lý dữ liệu JSON
import java.io.IOException // Dùng để xử lý lỗi I/O

/**
 * Lớp `OnlineModel` chịu trách nhiệm tải ảnh lên server để xử lý.
 * @param listener Interface lắng nghe các sự kiện từ model.
 */
class OnlineModel(private val listener: IModel) {

    // URL của server dùng để nhận ảnh và trả về kết quả
    private val serverUrl = "https://0bcc-171-243-48-28.ngrok-free.app/predict"

    // Tạo một instance của OkHttpClient để gửi HTTP requests
    private val client = OkHttpClient()

    /**
     * Phương thức `uploadImageToServer` dùng để gửi ảnh lên server và nhận kết quả.
     * @param imageBytes Mảng byte của ảnh cần tải lên.
     */
    internal fun uploadImageToServer(imageBytes: ByteArray) {
        // Khởi chạy coroutine trên luồng IO để tránh chặn luồng chính
        listener.coroutineScope.launch(Dispatchers.IO) {
            try {
                // Tạo request body dạng multipart/form-data để chứa ảnh và ngôn ngữ
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM) // Xác định kiểu dữ liệu là multipart
                    .addFormDataPart(
                        "file", "image.jpg",
                        imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull()) // Gửi ảnh với kiểu MIME là image/jpeg
                    )
                    .addFormDataPart("lang", listener.selectedLanguage) // Gửi thông tin ngôn ngữ kèm theo
                    .build()

                // Tạo request HTTP POST với body chứa ảnh
                val request = Request.Builder()
                    .url(serverUrl) // Đặt URL của server
                    .post(requestBody) // Gửi request POST
                    .build()

                // Thực hiện request và xử lý phản hồi
                client.newCall(request).execute().use { response ->
                    // Nếu phản hồi không thành công, ném ngoại lệ
                    if (!response.isSuccessful) throw IOException("Unexpected response: ${response.code}")

                    // Chuyển đổi phản hồi từ JSON thành đối tượng JSONObject
                    val jsonObject = JSONObject(response.body?.string() ?: "{}")
                    val caption = jsonObject.optString("caption", "No caption found") // Lấy giá trị của "caption"

                    // Chuyển về luồng Main để cập nhật UI
                    withContext(Dispatchers.Main) {
                        listener.showToast("Caption: $caption") // Hiển thị caption lên UI
                        listener.speakText(caption, false) // Đọc caption bằng Text-to-Speech
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    // Hiển thị thông báo lỗi tùy theo ngôn ngữ đã chọn
                    val errorMessage = if (listener.selectedLanguage == "vi") {
                        "Server bị lỗi, hãy thử lại hoặc tắt mạng để chuyển sang chế độ offline. Vui lòng nhấp lại màn hình để chụp ảnh!"
                    } else {
                        "Server error, please try again or turn off the network to go offline. Please click the screen again to take a photo!"
                    }
                    listener.speakText(errorMessage, false) // Đọc thông báo lỗi
                }
            }
        }
    }
}