package com.remotedev.pocketcode.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow

class VoiceInput(ctx: Context) {
    val text = MutableStateFlow("")
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(Listener()) }
    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    }

    fun start() { recognizer.startListening(intent) }
    fun stop() { recognizer.stopListening() }
    fun release() { recognizer.destroy() }

    private inner class Listener : RecognitionListener {
        override fun onResults(results: Bundle?) { results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text.value = it } }
        override fun onPartialResults(p: Bundle?) { p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text.value = it } }
        override fun onError(e: Int) {}
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(p: Float) {}
        override fun onBufferReceived(p: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(p: Int, p1: Bundle?) {}
    }
}
