package com.sprit.tvremote.tv

import com.google.protobuf.ByteString
import com.sprit.tvremote.proto.remote.RemoteAppLinkLaunchRequest
import com.sprit.tvremote.proto.remote.RemoteConfigure
import com.sprit.tvremote.proto.remote.RemoteDeviceInfo
import com.sprit.tvremote.proto.remote.RemoteDirection
import com.sprit.tvremote.proto.remote.RemoteEditInfo
import com.sprit.tvremote.proto.remote.RemoteImeBatchEdit
import com.sprit.tvremote.proto.remote.RemoteImeObject
import com.sprit.tvremote.proto.remote.RemoteKeyCode
import com.sprit.tvremote.proto.remote.RemoteKeyInject
import com.sprit.tvremote.proto.remote.RemoteMessage
import com.sprit.tvremote.proto.remote.RemotePingResponse
import com.sprit.tvremote.proto.remote.RemoteSetActive
import com.sprit.tvremote.proto.remote.RemoteVoiceBegin
import com.sprit.tvremote.proto.remote.RemoteVoiceEnd
import com.sprit.tvremote.proto.remote.RemoteVoicePayload
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

data class DeviceInfo(val manufacturer: String, val model: String, val version: String) {
    val title: String
        get() = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Телевизор" }
}

data class VolumeInfo(val level: Int, val max: Int, val muted: Boolean)

sealed interface TvEvent {
    data class Device(val info: DeviceInfo) : TvEvent
    data class Power(val isOn: Boolean) : TvEvent
    data class App(val packageName: String) : TvEvent
    data class Volume(val info: VolumeInfo) : TvEvent
}

/**
 * Сессия протокола пульта (порт 6466).
 *
 * Телевизор сам ведёт диалог: сначала присылает своё описание и набор поддерживаемых
 * возможностей, затем пингует каждые пять секунд и шлёт уведомления о питании, громкости и
 * текущем приложении. [run] крутит этот обмен до обрыва связи.
 */
class RemoteSession(
    private val socket: SSLSocket,
    private val onEvent: (TvEvent) -> Unit,
    /** Отладочный доступ ко всем входящим сообщениям — используется диагностикой. */
    private val onRawMessage: ((RemoteMessage) -> Unit)? = null,
    /** Что запрашиваем у телевизора; диагностика подменяет набор при поиске рабочего режима. */
    requestedFeatures: Int = DEFAULT_FEATURES,
) {

    private val stream = MessageStream(socket)

    /** При рукопожатии оставляем пересечение запрошенного с умениями телевизора. */
    private var activeFeatures = requestedFeatures

    // Счётчики поля ввода на телевизоре: без них он игнорирует отправленный текст.
    private var imeCounter = 0
    private var imeFieldCounter = 0

    // Номер голосовой сессии телевизор присылает сам, в ответ на нажатие «Поиск».
    private val voiceSessionIds = ArrayBlockingQueue<Int>(1)

    /** Умеет ли телевизор принимать голос — выясняется при рукопожатии. */
    val isVoiceSupported: Boolean
        get() = activeFeatures and FEATURE_VOICE == FEATURE_VOICE

    /** Читать сообщения до закрытия соединения. Блокирует вызывающий поток. */
    fun run() {
        while (true) {
            val raw = stream.read() ?: return
            handle(RemoteMessage.parseFrom(raw))
        }
    }

    fun close() = socket.closeQuietly()

    fun sendKey(keyCode: RemoteKeyCode, direction: RemoteDirection = RemoteDirection.SHORT) {
        send(
            RemoteMessage.newBuilder().setRemoteKeyInject(
                RemoteKeyInject.newBuilder()
                    .setKeyCode(keyCode)
                    .setDirection(direction),
            ),
        )
    }

    /** Вставить текст в открытое на телевизоре поле ввода (поиск, пароль Wi-Fi и т.п.). */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val position = text.length - 1
        send(
            RemoteMessage.newBuilder().setRemoteImeBatchEdit(
                RemoteImeBatchEdit.newBuilder()
                    .setImeCounter(imeCounter)
                    .setFieldCounter(imeFieldCounter)
                    .addEditInfo(
                        RemoteEditInfo.newBuilder()
                            .setInsert(1)
                            .setTextFieldStatus(
                                RemoteImeObject.newBuilder()
                                    .setStart(position)
                                    .setEnd(position)
                                    .setValue(text),
                            ),
                    ),
            ),
        )
    }

    /** Запустить приложение по id пакета или по готовой ссылке вида `youtube://…`. */
    fun launchApp(appIdOrLink: String) {
        val link = if (appIdOrLink.contains("://")) appIdOrLink else "market://launch?id=$appIdOrLink"
        send(
            RemoteMessage.newBuilder().setRemoteAppLinkLaunchRequest(
                RemoteAppLinkLaunchRequest.newBuilder().setAppLink(link),
            ),
        )
    }

    /**
     * Открыть голосовую сессию: нажимаем «Поиск», телевизор показывает ассистента и в ответ
     * присылает номер сессии — только после этого он связывает наш звук с распознаванием.
     *
     * Сессию, открытую без приглашения, телевизор принимает молча, но речь не распознаёт —
     * проверено на живом устройстве, поэтому без приглашения звук не отправляем вовсе.
     *
     * @return номер сессии или `null`, если телевизор не пригласил
     */
    fun startVoice(
        timeoutMs: Long = VOICE_START_TIMEOUT_MS,
        trigger: RemoteKeyCode = RemoteKeyCode.KEYCODE_SEARCH,
    ): Int? {
        voiceSessionIds.clear()
        sendKey(trigger)
        val sessionId = awaitVoiceInvite(timeoutMs) ?: return null
        openVoice(sessionId)
        return sessionId
    }

    /** Дождаться приглашения телевизора начать голосовую сессию. */
    fun awaitVoiceInvite(timeoutMs: Long): Int? = voiceSessionIds.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** Подтвердить телевизору начало голосовой сессии. */
    fun openVoice(sessionId: Int) {
        send(
            RemoteMessage.newBuilder().setRemoteVoiceBegin(
                RemoteVoiceBegin.newBuilder().setSessionId(sessionId),
            ),
        )
    }

    /**
     * Отправить записанный звук: 16-битный PCM, моно, 8000 Гц. Куски меньше 8 КБ телевизор
     * игнорирует — дополняем тишиной; куски больше 20 КБ обрывают соединение — делим.
     */
    fun sendVoiceChunk(chunk: ByteArray, sessionId: Int) {
        val padded = if (chunk.size < VOICE_CHUNK_MIN) chunk.copyOf(VOICE_CHUNK_MIN) else chunk
        var offset = 0
        while (offset < padded.size) {
            val end = minOf(offset + VOICE_CHUNK_MAX, padded.size)
            send(
                RemoteMessage.newBuilder().setRemoteVoicePayload(
                    RemoteVoicePayload.newBuilder()
                        .setSessionId(sessionId)
                        .setSamples(ByteString.copyFrom(padded, offset, end - offset)),
                ),
            )
            offset = end
        }
    }

    fun endVoice(sessionId: Int) {
        send(
            RemoteMessage.newBuilder().setRemoteVoiceEnd(
                RemoteVoiceEnd.newBuilder().setSessionId(sessionId),
            ),
        )
    }

    private fun handle(message: RemoteMessage) {
        onRawMessage?.invoke(message)
        // Проверяем отдельно от when: номер голосовой сессии не должен потеряться, даже
        // если телевизор пришлёт его вместе с другим полем.
        if (message.hasRemoteVoiceBegin()) voiceSessionIds.offer(message.remoteVoiceBegin.sessionId)
        when {
            message.hasRemoteConfigure() -> {
                val info = message.remoteConfigure.deviceInfo
                onEvent(TvEvent.Device(DeviceInfo(info.vendor, info.model, info.appVersion)))
                activeFeatures = activeFeatures and message.remoteConfigure.code1
                send(
                    RemoteMessage.newBuilder().setRemoteConfigure(
                        RemoteConfigure.newBuilder()
                            .setCode1(activeFeatures)
                            .setDeviceInfo(
                                RemoteDeviceInfo.newBuilder()
                                    .setUnknown1(1)
                                    .setUnknown2("1")
                                    .setPackageName("atvremote")
                                    .setAppVersion("1.0.0"),
                            ),
                    ),
                )
            }

            message.hasRemoteSetActive() -> send(
                RemoteMessage.newBuilder().setRemoteSetActive(
                    RemoteSetActive.newBuilder().setActive(activeFeatures),
                ),
            )

            message.hasRemotePingRequest() -> send(
                RemoteMessage.newBuilder().setRemotePingResponse(
                    RemotePingResponse.newBuilder().setVal1(message.remotePingRequest.val1),
                ),
            )

            message.hasRemoteStart() -> onEvent(TvEvent.Power(message.remoteStart.started))

            message.hasRemoteSetVolumeLevel() -> {
                val volume = message.remoteSetVolumeLevel
                onEvent(TvEvent.Volume(VolumeInfo(volume.volumeLevel, volume.volumeMax, volume.volumeMuted)))
            }

            message.hasRemoteImeKeyInject() ->
                onEvent(TvEvent.App(message.remoteImeKeyInject.appInfo.appPackage))

            message.hasRemoteImeBatchEdit() -> {
                imeCounter = message.remoteImeBatchEdit.imeCounter
                imeFieldCounter = message.remoteImeBatchEdit.fieldCounter
            }
        }
    }

    private fun send(builder: RemoteMessage.Builder) = stream.write(builder.build().toByteArray())

    companion object {
        /** Всё, что нужно пульту: пинг, кнопки, ввод текста, голос, питание, громкость, запуск приложений. */
        const val DEFAULT_FEATURES = 1 or 2 or 4 or 8 or 32 or 64 or 512

        const val FEATURE_PING = 1 shl 0
        const val FEATURE_KEY = 1 shl 1
        const val FEATURE_IME = 1 shl 2
        const val FEATURE_VOICE = 1 shl 3
        const val FEATURE_POWER = 1 shl 5
        const val FEATURE_VOLUME = 1 shl 6
        const val FEATURE_APP_LINK = 1 shl 9

        const val VOICE_START_TIMEOUT_MS = 3_000L
        const val VOICE_CHUNK_MIN = 8 * 1024
        const val VOICE_CHUNK_MAX = 20 * 1024
    }
}
