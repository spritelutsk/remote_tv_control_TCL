package com.sprit.tvremote.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Распознавание речи на самом телефоне.
 *
 * Телевизор объявляет поддержку голоса, но присланный ему аудиопоток не распознаёт (проверено
 * на TCL BeyondTV), поэтому речь превращает в текст телефон, а телевизору уходит уже готовый
 * запрос. Работает системный распознаватель — тот же, что в клавиатуре Google.
 */
class SpeechToText(private val context: Context) {

    interface Listener {
        /** Промежуточный результат: показываем, пока человек говорит. */
        fun onPartial(text: String)

        fun onResult(text: String)

        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Начать слушать. Вызывать только из главного потока — этого требует SpeechRecognizer. */
    fun start(listener: Listener) {
        stop()
        if (!isAvailable) {
            listener.onError("На телефоне нет распознавания речи")
            return
        }
        val speech = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results.firstText()
                if (text.isNullOrBlank()) listener.onError("Не расслышал") else listener.onResult(text)
                release()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstText()?.let(listener::onPartial)
            }

            override fun onError(error: Int) {
                listener.onError(describe(error))
                release()
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speech.startListening(intent)
    }

    /** Отпустили кнопку: договорить дадим, результат придёт в слушатель. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun Bundle?.firstText(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Не удалось записать звук"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет связи для распознавания"
        SpeechRecognizer.ERROR_NO_MATCH -> "Не расслышал"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ничего не услышал"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Язык не поддерживается распознавателем"
        else -> "Распознать не удалось"
    }
}
