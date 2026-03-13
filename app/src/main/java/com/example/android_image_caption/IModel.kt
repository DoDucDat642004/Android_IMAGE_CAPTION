package com.example.android_image_caption

import kotlinx.coroutines.CoroutineScope

interface IModel {
    fun showToast(message: String)
    fun speakText(text: String, isBlockUI: Boolean)
    val coroutineScope: CoroutineScope
    var selectedLanguage: String
}