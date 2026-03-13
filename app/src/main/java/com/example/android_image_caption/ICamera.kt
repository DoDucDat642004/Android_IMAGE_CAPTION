package com.example.android_image_caption

import android.graphics.Bitmap

interface ICamera {
    fun hasInternetConnection(): Boolean
    fun uploadImageToServer(imageBytes: ByteArray)
    fun processImageOffline(bitmap: Bitmap)
}