package com.example.android_image_caption

// Import các thư viện cần thiết
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import org.pytorch.*
import org.pytorch.torchvision.TensorImageUtils
import java.io.*

/**
 * Lớp `OfflineModel` chịu trách nhiệm tải, lưu trữ và thực thi mô hình AI (PyTorch) trên thiết bị.
 * @param context Ngữ cảnh ứng dụng để truy cập tài nguyên
 * @param listener Interface để giao tiếp với UI
 */
class OfflineModel(private val context: Context, private val listener: IModel) {

    // Lưu trữ SharedPreferences để cache thông tin model
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("model_cache", Context.MODE_PRIVATE)

    // Biến lưu trữ các mô hình đã tải
    private var encoderModule: Module? = null
    private var decoderViModule: Module? = null
    private var decoderEnModule: Module? = null

    // Tokenizer mapping để giải mã kết quả mô hình
    private var tokenizerMappingVi: Map<Long, String> = emptyMap()
    private var tokenizerMappingEn: Map<Long, String> = emptyMap()

    /**
     * Tải mô hình PyTorch và tokenizer mapping.
     * Gọi `onLoadComplete` khi hoàn tất và cập nhật tiến trình qua `onProgressUpdate`.
     */
    @SuppressLint("UseKtx")
    internal fun loadModel(onLoadComplete: () -> Unit, onProgressUpdate: (Int) -> Unit) {
        listener.coroutineScope.launch(Dispatchers.IO) {
            try {
                onProgressUpdate(0) // Bắt đầu tải (0%)

                // Tải tokenizer và mô hình đồng thời bằng `async` để tăng tốc độ tải
                val tokenizerDeferred = async { loadAllTokenizerMappings() }
                val encoderDeferred = async { ensureModelExists("swinv2_tiny_encoder_mobile.ptl") }
                val decoderViDeferred = async { ensureModelExists("decoder_vi_tiny_mobile.ptl") }
                val decoderEnDeferred = async { ensureModelExists("decoder_en_tiny_mobile.ptl") }

                // Đợi tất cả các mô hình được tải
                val (finalEncoderPath, finalDecoderViPath, finalDecoderEnPath) = awaitAll(
                    encoderDeferred, decoderViDeferred, decoderEnDeferred
                )

                if (finalEncoderPath == null || finalDecoderViPath == null || finalDecoderEnPath == null) {
                    Log.e("TorchModel", "Không tìm thấy một số model.")
                    listener.showToast("Lỗi tải model, vui lòng kiểm tra lại!")
                    return@launch
                }

                // Tiến trình tải từng phần
                onProgressUpdate(10)
                encoderModule = Module.load(finalEncoderPath)
                onProgressUpdate(30)
                decoderViModule = Module.load(finalDecoderViPath)
                onProgressUpdate(60)
                decoderEnModule = Module.load(finalDecoderEnPath)
                onProgressUpdate(90)

                // Nạp tokenizer mapping
                val (viMapping, enMapping) = tokenizerDeferred.await()
                tokenizerMappingVi = viMapping
                tokenizerMappingEn = enMapping

                // Lưu trạng thái mô hình đã tải
                sharedPreferences.edit().putBoolean("model_loaded", true).apply()
                Log.d("TorchModel", "Tất cả model & tokenizer đã tải thành công!")

                onProgressUpdate(100) // Hoàn thành tải

            } catch (e: Exception) {
                Log.e("TorchModel", "Lỗi khi tải model: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { onLoadComplete() }
            }
        }
    }

    /**
     * Kiểm tra và đảm bảo mô hình tồn tại trong bộ nhớ cache.
     * Nếu chưa có, sao chép mô hình từ `assets` vào bộ nhớ cache.
     */
    @SuppressLint("UseKtx")
    private fun ensureModelExists(modelName: String): String? {
        val outputFile = File(context.cacheDir, modelName)
        val savedFileSize = sharedPreferences.getLong("${modelName}_size", -1L)

        // Kiểm tra xem mô hình đã tồn tại chưa
        if (outputFile.exists() && outputFile.length() == savedFileSize) {
            Log.d("TorchModel", "Model $modelName đã có sẵn.")
            return outputFile.absolutePath
        }

        // Nếu chưa có, sao chép mô hình từ `assets`
        return copyModelToInternalStorage(modelName)?.also {
            sharedPreferences.edit()
                .putLong("${modelName}_size", outputFile.length())
                .apply()
        }
    }

    /**
     * Sao chép mô hình từ thư mục `assets` vào bộ nhớ trong của ứng dụng.
     */
    @SuppressLint("UseKtx")
    private fun copyModelToInternalStorage(modelName: String): String? {
        val assetPath = "models/$modelName"
        val outputFile = File(context.cacheDir, modelName)

        return try {
            context.assets.open(assetPath).use { inputStream ->
                FileOutputStream(outputFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                }
            }
            Log.d("TorchModel", "Model $modelName đã copy thành công vào bộ nhớ.")
            outputFile.absolutePath
        } catch (e: IOException) {
            Log.e("TorchModel", "Lỗi sao chép model: ${e.message}")
            null
        }
    }

    /**
     * Nạp tokenizer mapping từ file JSON.
     */
    private fun loadAllTokenizerMappings(): Pair<Map<Long, String>, Map<Long, String>> {
        return try {
            val inputStream = context.assets.open("tokenizer_mapping.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)

            val tokenizerVi = jsonObject.optJSONObject("vi") ?: JSONObject()
            val tokenizerEn = jsonObject.optJSONObject("en") ?: JSONObject()

            val viMapping = tokenizerVi.keys().asSequence().associate { it.toLong() to tokenizerVi.getString(it) }
            val enMapping = tokenizerEn.keys().asSequence().associate { it.toLong() to tokenizerEn.getString(it) }

            Pair(viMapping, enMapping)
        } catch (e: Exception) {
            Log.e("TorchModel", "Lỗi load tokenizer: ${e.message}")
            Pair(emptyMap(), emptyMap())
        }
    }

    /**
     * Xử lý ảnh đầu vào, chạy mô hình và tạo kết quả đầu ra.
     */
    internal fun predict(bitmap: Bitmap, lang: String) {
        listener.coroutineScope.launch(Dispatchers.IO) {
            val inputTensor = preprocessImage(bitmap)

            val encoderOutputs = encoderModule!!.forward(IValue.from(inputTensor)).toTensor()
            val decoderModule = if (lang == "vi") decoderViModule else decoderEnModule
            val decoderOutputs = decoderModule!!.forward(IValue.from(encoderOutputs)).toTensor()
            val outputTokens = decoderOutputs.dataAsLongArray

            val caption = convertTokensToWords(outputTokens, lang)

            listener.showToast("Kết quả: $caption")
            listener.speakText(caption, false)
        }
    }

    /**
     * Chuyển đổi token ID thành chuỗi văn bản.
     */
    private fun convertTokensToWords(predictedTokens: LongArray, lang: String): String {
        val tokenizerMapping = if (lang == "vi") tokenizerMappingVi else tokenizerMappingEn
        return predictedTokens.joinToString(" ") { tokenizerMapping[it] ?: "" }
            .replace("Ġ", "")
            .replace("<s>", "")
            .replace("</s>", "")
            .trim()
    }

    /**
     * Tiền xử lý ảnh trước khi đưa vào mô hình.
     */
    @SuppressLint("UseKtx")
    private fun preprocessImage(bitmap: Bitmap): Tensor {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        return TensorImageUtils.bitmapToFloat32Tensor(
            resizedBitmap,
            floatArrayOf(0.485f, 0.456f, 0.406f), // Mean normalization
            floatArrayOf(0.229f, 0.224f, 0.225f)  // Std normalization
        )
    }
}