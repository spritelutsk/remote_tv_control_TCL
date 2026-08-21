package com.sprit.tvremote.tv

import android.content.Context
import com.sprit.tvremote.proto.remote.RemoteDirection
import com.sprit.tvremote.proto.remote.RemoteKeyCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

enum class ConnectionStatus { Disconnected, Connecting, Pairing, Connected, Failed }

data class TvState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val host: String = "",
    val message: String = "",
    val device: DeviceInfo? = null,
    val isOn: Boolean? = null,
    val currentApp: String? = null,
    val volume: VolumeInfo? = null,
) {
    val isConnected: Boolean get() = status == ConnectionStatus.Connected
}

/** Запрос PIN-кода к пользователю: экран телевизора показывает код, диалог его принимает. */
class PinRequest(
    val deviceName: String,
    val retry: Boolean,
    private val answer: CompletableDeferred<String?>,
) {
    fun submit(pin: String) {
        answer.complete(pin)
    }

    fun cancel() {
        answer.complete(null)
    }
}

/** Признак того, что телевизор не узнал наш сертификат и нужно пройти спаривание заново. */
private class NeedsPairing : Exception()

/**
 * Соединение с телевизором и его состояние.
 *
 * Держит одну живую сессию [RemoteSession], сам восстанавливает связь после обрыва (телевизор
 * рвёт её, например, при выключении) и запускает спаривание, когда сертификат отвергнут.
 * Команды отправляются в отдельном однопоточном контексте, поэтому порядок нажатий сохраняется.
 */
class TvController(context: Context, private val scope: CoroutineScope) {

    private val certStore = CertStore(context.applicationContext)
    private val sender = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tv-sender").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val _state = MutableStateFlow(TvState())
    val state: StateFlow<TvState> = _state.asStateFlow()

    private val _pinRequest = MutableStateFlow<PinRequest?>(null)
    val pinRequest: StateFlow<PinRequest?> = _pinRequest.asStateFlow()

    private var connectionJob: Job? = null

    @Volatile
    private var session: RemoteSession? = null

    fun connect(host: String) {
        val target = host.trim()
        if (target.isEmpty()) return
        stop()
        connectionJob = scope.launch(Dispatchers.IO) { maintainConnection(target) }
    }

    fun disconnect() {
        stop()
        _state.value = TvState(message = "Отключено")
    }

    /** Забыть спаривание: телевизор попросит новый PIN при следующем подключении. */
    fun forgetPairing() {
        stop()
        certStore.reset()
        _state.value = TvState(message = "Спаривание сброшено")
    }

    fun sendKey(keyCode: RemoteKeyCode, direction: RemoteDirection = RemoteDirection.SHORT) =
        execute { it.sendKey(keyCode, direction) }

    /** Долгое нажатие: телевизор ждёт отдельные события начала и конца. */
    fun sendLongKey(keyCode: RemoteKeyCode, holdMs: Long = 700) {
        val current = session ?: return
        scope.launch(sender) {
            try {
                current.sendKey(keyCode, RemoteDirection.START_LONG)
                // delay, а не sleep: очередь команд не должна вставать на время удержания.
                delay(holdMs)
                current.sendKey(keyCode, RemoteDirection.END_LONG)
            } catch (_: IOException) {
                // Обрыв заметит цикл чтения и переподключится сам.
            }
        }
    }

    fun sendText(text: String) = execute { it.sendText(text) }

    fun launchApp(appIdOrLink: String) = execute { it.launchApp(appIdOrLink) }

    /**
     * Найти на телевизоре по строке. Поиск открываем ссылкой в YouTube: собственный поиск
     * телевизора текст извне не принимает — он не объявляет поле ввода.
     */
    fun searchOnTv(query: String) {
        val text = query.trim()
        if (text.isEmpty()) return
        val encoded = java.net.URLEncoder.encode(text, "UTF-8")
        _state.update { it.copy(message = "Ищу: $text") }
        launchApp("vnd.youtube://results?search_query=$encoded")
    }

    fun shutdown() {
        stop()
        sender.close()
    }

    private fun stop() {
        _pinRequest.value?.cancel()
        connectionJob?.cancel()
        connectionJob = null
        session?.close()
        session = null
    }

    private fun execute(action: (RemoteSession) -> Unit) {
        val current = session ?: return
        scope.launch(sender) {
            try {
                action(current)
            } catch (_: IOException) {
                // Обрыв заметит цикл чтения и переподключится сам.
            }
        }
    }

    private suspend fun maintainConnection(host: String) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            try {
                _state.update {
                    it.copy(status = ConnectionStatus.Connecting, host = host, message = "Подключаюсь к $host…")
                }
                runSession(host)
                backoffMs = INITIAL_BACKOFF_MS
                _state.update {
                    it.copy(
                        status = ConnectionStatus.Connecting,
                        message = "Связь потеряна, переподключаюсь…",
                        isOn = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NeedsPairing) {
                if (!pair(host)) return
                backoffMs = INITIAL_BACKOFF_MS
                continue
            } catch (error: Exception) {
                _state.update {
                    it.copy(status = ConnectionStatus.Connecting, message = describe(error, host), isOn = null)
                }
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** Одна сессия: живёт, пока телевизор на связи. Возврат означает обрыв. */
    private fun runSession(host: String) {
        val socket = try {
            connectTls(certStore.createSslContext(), host, REMOTE_PORT, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        } catch (_: SSLException) {
            // TLS 1.2: незнакомый сертификат отвергается прямо в рукопожатии.
            throw NeedsPairing()
        }

        var sawTraffic = false
        val active = RemoteSession(socket, onEvent = { event ->
            sawTraffic = true
            apply(event)
        })
        session = active
        try {
            active.run()
        } catch (error: IOException) {
            // А по TLS 1.3 сервер проверяет сертификат уже после рукопожатия, поэтому отказ
            // прилетает сюда: `certificate_unknown` на первом же чтении. Молчаливый обрыв до
            // первого сообщения означает то же самое — телевизор нас не узнал.
            if (!sawTraffic) throw NeedsPairing()
            throw error
        } finally {
            session = null
            active.close()
        }
        if (!sawTraffic) throw NeedsPairing()
    }

    private suspend fun pair(host: String): Boolean {
        _state.update {
            it.copy(status = ConnectionStatus.Pairing, host = host, message = "Нужно спаривание с телевизором")
        }
        return try {
            Pairing.pair(host, certStore.createSslContext(), certStore.clientCertificate()) { deviceName, retry ->
                requestPin(deviceName, retry)
            }
            _state.update { it.copy(message = "Спаривание прошло успешно") }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: PairingCancelled) {
            _state.value = TvState(host = host, message = "Спаривание отменено")
            false
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    status = ConnectionStatus.Failed,
                    message = error.message ?: "Спаривание не удалось",
                )
            }
            false
        }
    }

    private suspend fun requestPin(deviceName: String, retry: Boolean): String? {
        val answer = CompletableDeferred<String?>()
        _pinRequest.value = PinRequest(deviceName, retry, answer)
        return try {
            answer.await()
        } finally {
            withContext(NonCancellable) { _pinRequest.value = null }
        }
    }

    private fun apply(event: TvEvent) = _state.update { current ->
        val connected = current.copy(status = ConnectionStatus.Connected, message = "Подключено")
        when (event) {
            is TvEvent.Device -> connected.copy(device = event.info)
            is TvEvent.Power -> connected.copy(isOn = event.isOn)
            is TvEvent.App -> connected.copy(currentApp = event.packageName)
            is TvEvent.Volume -> connected.copy(volume = event.info)
        }
    }

    private fun describe(error: Exception, host: String): String = when (error) {
        is UnknownHostException -> "Не удалось найти $host"
        is ConnectException, is NoRouteToHostException -> "Телевизор $host не отвечает, жду…"
        is SocketTimeoutException -> "Телевизор молчит, переподключаюсь…"
        else -> error.message ?: "Ошибка связи, переподключаюсь…"
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        // Телевизор пингует каждые пять секунд, поэтому тишина дольше — признак мёртвой связи.
        const val READ_TIMEOUT_MS = 20_000
        const val INITIAL_BACKOFF_MS = 700L
        const val MAX_BACKOFF_MS = 15_000L
    }
}
