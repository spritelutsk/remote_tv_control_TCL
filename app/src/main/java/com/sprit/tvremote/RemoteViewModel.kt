package com.sprit.tvremote

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sprit.tvremote.tv.DiscoveredTv
import com.sprit.tvremote.tv.TvController
import com.sprit.tvremote.tv.TvDiscovery
import com.sprit.tvremote.tv.SpeechToText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

class RemoteViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = application.getSharedPreferences("tv-remote", Context.MODE_PRIVATE)

    val controller = TvController(application, viewModelScope)
    val state = controller.state
    val pinRequest = controller.pinRequest

    private val speech = SpeechToText(application)

    /** Что распознал телефон: показываем, пока человек говорит и сразу после. */
    var heardText by mutableStateOf("")
        private set

    /** Идёт ли сейчас распознавание — по этому подсвечивается кнопка микрофона. */
    var isListening by mutableStateOf(false)
        private set

    /** Телевизоры, найденные в сети. Поиск идёт, только пока открыт диалог подключения. */
    val discovered: StateFlow<List<DiscoveredTv>> = TvDiscovery(application)
        .discover()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), emptyList())

    var host by mutableStateOf(preferences.getString(KEY_HOST, "").orEmpty())
        private set

    init {
        if (host.isNotBlank()) controller.connect(host)
    }

    fun connect(newHost: String) {
        host = newHost.trim()
        preferences.edit().putString(KEY_HOST, host).apply()
        controller.connect(host)
    }

    fun reconnect() {
        if (host.isNotBlank()) controller.connect(host)
    }

    fun forgetPairing() = controller.forgetPairing()

    /**
     * Голосовой поиск: телефон превращает речь в текст и открывает на телевизоре поиск с этим
     * запросом. Так сделано потому, что аудиопоток, отправленный телевизору по протоколу, он
     * принимает, но не распознаёт.
     *
     * Разрешение на микрофон запрашивает экран.
     */
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun startVoice() {
        heardText = ""
        isListening = true
        speech.start(object : SpeechToText.Listener {
            override fun onPartial(text: String) {
                heardText = text
            }

            override fun onResult(text: String) {
                heardText = text
                isListening = false
                controller.searchOnTv(text)
            }

            override fun onError(message: String) {
                heardText = message
                isListening = false
            }
        })
    }

    fun stopVoice() = speech.stop()

    override fun onCleared() {
        speech.release()
        controller.shutdown()
    }

    private companion object {
        const val KEY_HOST = "host"
    }
}
