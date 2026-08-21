package com.sprit.tvremote.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sprit.tvremote.RemoteViewModel
import com.sprit.tvremote.proto.remote.RemoteKeyCode
import com.sprit.tvremote.tv.ConnectionStatus
import com.sprit.tvremote.tv.DiscoveredTv
import com.sprit.tvremote.tv.TvState

private val APP_PRESETS = listOf(
    "YouTube" to "com.google.android.youtube.tv",
    "MEGOGO" to "com.megogo.application",
    "Netflix" to "com.netflix.ninja",
    "Кинопоиск" to "ru.kinopoisk.tv",
    "Play Маркет" to "com.android.vending",
)

private enum class Panel { None, Numbers, Text, Apps }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(viewModel: RemoteViewModel) {
    val state by viewModel.state.collectAsState()
    val pinRequest by viewModel.pinRequest.collectAsState()
    val isListening = viewModel.isListening

    val context = LocalContext.current
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val askForMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted = it
    }

    var showConnect by remember { mutableStateOf(viewModel.host.isBlank()) }
    var showMenu by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.None) }

    val send: (RemoteKeyCode) -> Unit = { viewModel.controller.sendKey(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.device?.title ?: "Пульт Android TV",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            statusLine(state, viewModel.host),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    StatusDot(state)
                    IconButton(onClick = { showConnect = true }) {
                        Icon(TvIcons.Wifi, contentDescription = "Подключение")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(TvIcons.More, contentDescription = "Ещё")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Переподключиться") },
                                onClick = {
                                    showMenu = false
                                    viewModel.reconnect()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Сбросить спаривание") },
                                onClick = {
                                    showMenu = false
                                    viewModel.forgetPairing()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .padding(insets)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusCard(state, viewModel.heardText)

            KeyRow {
                KeyButton(
                    onClick = { send(RemoteKeyCode.KEYCODE_POWER) },
                    modifier = Modifier.weight(1f).aspectRatio(1.6f),
                    icon = TvIcons.Power,
                    contentDescription = "Питание",
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                KeyButton(
                    onClick = { send(RemoteKeyCode.KEYCODE_HOME) },
                    modifier = Modifier.weight(1f).aspectRatio(1.6f),
                    icon = TvIcons.Home,
                    contentDescription = "Домой",
                    shape = RoundedCornerShape(20.dp),
                )
                KeyButton(
                    onClick = { send(RemoteKeyCode.KEYCODE_BACK) },
                    modifier = Modifier.weight(1f).aspectRatio(1.6f),
                    icon = TvIcons.Back,
                    contentDescription = "Назад",
                    shape = RoundedCornerShape(20.dp),
                )
                KeyButton(
                    // Оболочки Android TV чаще всего открывают контекстное меню именно так.
                    onClick = { viewModel.controller.sendLongKey(RemoteKeyCode.KEYCODE_DPAD_CENTER) },
                    modifier = Modifier.weight(1f).aspectRatio(1.6f),
                    icon = TvIcons.Menu,
                    contentDescription = "Меню",
                    shape = RoundedCornerShape(20.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Rocker(
                    caption = "ГРОМК",
                    // Плюс и минус различимы с одного взгляда, в отличие от двух похожих
                    // «динамиков» — назначение качельки объясняет подпись.
                    topIcon = TvIcons.Plus,
                    bottomIcon = TvIcons.Minus,
                    onTop = { send(RemoteKeyCode.KEYCODE_VOLUME_UP) },
                    onBottom = { send(RemoteKeyCode.KEYCODE_VOLUME_DOWN) },
                    modifier = Modifier.width(66.dp).height(190.dp),
                )
                DirectionPad(
                    onUp = { send(RemoteKeyCode.KEYCODE_DPAD_UP) },
                    onDown = { send(RemoteKeyCode.KEYCODE_DPAD_DOWN) },
                    onLeft = { send(RemoteKeyCode.KEYCODE_DPAD_LEFT) },
                    onRight = { send(RemoteKeyCode.KEYCODE_DPAD_RIGHT) },
                    onOk = { send(RemoteKeyCode.KEYCODE_DPAD_CENTER) },
                    onOkLongClick = { viewModel.controller.sendLongKey(RemoteKeyCode.KEYCODE_DPAD_CENTER) },
                    modifier = Modifier.weight(1f),
                )
                Rocker(
                    caption = "КАНАЛ",
                    topIcon = TvIcons.Up,
                    bottomIcon = TvIcons.Down,
                    onTop = { send(RemoteKeyCode.KEYCODE_CHANNEL_UP) },
                    onBottom = { send(RemoteKeyCode.KEYCODE_CHANNEL_DOWN) },
                    modifier = Modifier.width(66.dp).height(190.dp),
                )
            }

            KeyRow {
                KeyButton(
                    onClick = { send(RemoteKeyCode.KEYCODE_VOLUME_MUTE) },
                    modifier = Modifier.weight(1f).aspectRatio(1.6f),
                    icon = TvIcons.Mute,
                    contentDescription = "Без звука",
                    shape = RoundedCornerShape(20.dp),
                )
                KeyButton(
                    onClick = { send(RemoteKeyCode.KEYCODE_SEARCH) },
                    modifier = Modifier.weight(1f).aspectRatio(1.6f),
                    icon = TvIcons.Search,
                    contentDescription = "Поиск",
                    shape = RoundedCornerShape(20.dp),
                )
                KeyButton(
                    // Голосовой поиск работает, пока палец на кнопке: отпустил — телефон
                    // дораспознаёт фразу и открывает поиск на телевизоре.
                    onClick = {},
                    onHoldStart = {
                        if (micGranted) viewModel.startVoice() else askForMic.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onHoldEnd = { if (micGranted) viewModel.stopVoice() },
                    modifier = Modifier.weight(1f).aspectRatio(1.6f),
                    icon = TvIcons.Assistant,
                    contentDescription = "Голосовой поиск: нажмите и говорите",
                    shape = RoundedCornerShape(20.dp),
                    containerColor = if (isListening) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (isListening) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                KeyButton(
                    onClick = { send(RemoteKeyCode.KEYCODE_SETTINGS) },
                    modifier = Modifier.weight(1f).aspectRatio(1.6f),
                    icon = TvIcons.Settings,
                    contentDescription = "Настройки",
                    shape = RoundedCornerShape(20.dp),
                )
            }

            KeyRow {
                KeyButton(
                    onClick = { send(RemoteKeyCode.KEYCODE_MEDIA_PREVIOUS) },
                    modifier = Modifier.weight(1f).aspectRatio(1.4f),
                    icon = TvIcons.Previous,
                    contentDescription = "Предыдущее",
                    shape = RoundedCornerShape(18.dp),
                )
                KeyButton(
                    onClick = { send(RemoteKeyCode.KEYCODE_MEDIA_REWIND) },
                    modifier = Modifier.weight(1f).aspectRatio(1.4f),
                    icon = TvIcons.Rewind,
                    contentDescription = "Назад",
                    shape = RoundedCornerShape(18.dp),
                    repeatable = true,
                )
                KeyButton(
                    onClick = { send(RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE) },
                    modifier = Modifier.weight(1.2f).aspectRatio(1.2f),
                    icon = TvIcons.Play,
                    contentDescription = "Пуск или пауза",
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    iconSize = 30,
                )
                KeyButton(
                    onClick = { send(RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD) },
                    modifier = Modifier.weight(1f).aspectRatio(1.4f),
                    icon = TvIcons.Forward,
                    contentDescription = "Вперёд",
                    shape = RoundedCornerShape(18.dp),
                    repeatable = true,
                )
                KeyButton(
                    onClick = { send(RemoteKeyCode.KEYCODE_MEDIA_NEXT) },
                    modifier = Modifier.weight(1f).aspectRatio(1.4f),
                    icon = TvIcons.Next,
                    contentDescription = "Следующее",
                    shape = RoundedCornerShape(18.dp),
                )
            }

            KeyRow {
                PanelTab("Цифры", TvIcons.Numbers, panel == Panel.Numbers, Modifier.weight(1f)) {
                    panel = if (panel == Panel.Numbers) Panel.None else Panel.Numbers
                }
                PanelTab("Текст", TvIcons.Keyboard, panel == Panel.Text, Modifier.weight(1f)) {
                    panel = if (panel == Panel.Text) Panel.None else Panel.Text
                }
                PanelTab("Приложения", TvIcons.Apps, panel == Panel.Apps, Modifier.weight(1f)) {
                    panel = if (panel == Panel.Apps) Panel.None else Panel.Apps
                }
            }

            AnimatedVisibility(panel == Panel.Numbers) { NumberPad(send) }
            AnimatedVisibility(panel == Panel.Text) { TextPanel(viewModel) }
            AnimatedVisibility(panel == Panel.Apps) { AppsPanel(viewModel) }

            Box(Modifier.height(12.dp))
        }
    }

    if (showConnect) {
        val discovered by viewModel.discovered.collectAsState()
        ConnectDialog(
            currentHost = viewModel.host,
            discovered = discovered,
            onDismiss = { showConnect = false },
            onConnect = {
                showConnect = false
                viewModel.connect(it)
            },
        )
    }

    pinRequest?.let { request ->
        PinDialog(
            deviceName = request.deviceName,
            retry = request.retry,
            onSubmit = request::submit,
            onCancel = request::cancel,
        )
    }
}

@Composable
private fun StatusDot(state: TvState) {
    val color = when (state.status) {
        ConnectionStatus.Connected -> if (state.isOn == false) Color(0xFFFFB300) else Color(0xFF4CAF50)
        ConnectionStatus.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    if (state.status == ConnectionStatus.Connecting) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    } else {
        Surface(modifier = Modifier.size(10.dp), shape = CircleShape, color = color) {}
    }
}

@Composable
private fun StatusCard(state: TvState, heard: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (heard.isBlank()) TvIcons.Tv else TvIcons.Assistant, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(
                    heard.ifBlank { state.message.ifBlank { "Не подключено" } },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(state.currentApp?.takeIf { it.isNotBlank() } ?: "приложение неизвестно")
                        state.volume?.let { volume ->
                            append("  ·  громкость ${volume.level}/${volume.max}")
                            if (volume.muted) append(" (без звука)")
                        }
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PanelTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    KeyButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        icon = icon,
        caption = label,
        shape = RoundedCornerShape(14.dp),
        containerColor = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        iconSize = 18,
    )
}

@Composable
private fun NumberPad(send: (RemoteKeyCode) -> Unit) {
    val digits = listOf(
        RemoteKeyCode.KEYCODE_1, RemoteKeyCode.KEYCODE_2, RemoteKeyCode.KEYCODE_3,
        RemoteKeyCode.KEYCODE_4, RemoteKeyCode.KEYCODE_5, RemoteKeyCode.KEYCODE_6,
        RemoteKeyCode.KEYCODE_7, RemoteKeyCode.KEYCODE_8, RemoteKeyCode.KEYCODE_9,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        digits.chunked(3).forEachIndexed { rowIndex, row ->
            KeyRow {
                row.forEachIndexed { index, code ->
                    KeyButton(
                        onClick = { send(code) },
                        modifier = Modifier.weight(1f).aspectRatio(1.7f),
                        label = (rowIndex * 3 + index + 1).toString(),
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }
        }
        KeyRow {
            KeyButton(
                onClick = { send(RemoteKeyCode.KEYCODE_DEL) },
                modifier = Modifier.weight(1f).aspectRatio(1.7f),
                label = "⌫",
                shape = RoundedCornerShape(16.dp),
            )
            KeyButton(
                onClick = { send(RemoteKeyCode.KEYCODE_0) },
                modifier = Modifier.weight(1f).aspectRatio(1.7f),
                label = "0",
                shape = RoundedCornerShape(16.dp),
            )
            KeyButton(
                onClick = { send(RemoteKeyCode.KEYCODE_ENTER) },
                modifier = Modifier.weight(1f).aspectRatio(1.7f),
                label = "↵",
                shape = RoundedCornerShape(16.dp),
            )
        }
        KeyRow {
            KeyButton(
                onClick = { send(RemoteKeyCode.KEYCODE_TV_INPUT) },
                modifier = Modifier.weight(1f).height(44.dp),
                icon = TvIcons.Source,
                caption = "Источник",
                shape = RoundedCornerShape(14.dp),
                iconSize = 18,
            )
            KeyButton(
                onClick = { send(RemoteKeyCode.KEYCODE_INFO) },
                modifier = Modifier.weight(1f).height(44.dp),
                icon = TvIcons.Info,
                caption = "Инфо",
                shape = RoundedCornerShape(14.dp),
                iconSize = 18,
            )
            KeyButton(
                onClick = { send(RemoteKeyCode.KEYCODE_GUIDE) },
                modifier = Modifier.weight(1f).height(44.dp),
                icon = TvIcons.Numbers,
                caption = "Гид",
                shape = RoundedCornerShape(14.dp),
                iconSize = 18,
            )
        }
    }
}

@Composable
private fun TextPanel(viewModel: RemoteViewModel) {
    var text by remember { mutableStateOf("") }
    val send = {
        if (text.isNotEmpty()) {
            viewModel.controller.sendText(text)
            text = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                label = { Text("Текст в поле на экране ТВ") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            KeyButton(
                onClick = send,
                modifier = Modifier.size(56.dp),
                icon = TvIcons.Send,
                contentDescription = "Отправить текст",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            "Работает, когда на телевизоре открыто поле ввода — например, поиск.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppsPanel(viewModel: RemoteViewModel) {
    var appId by remember { mutableStateOf("") }
    val launch = {
        if (appId.isNotBlank()) {
            viewModel.controller.launchApp(appId.trim())
            appId = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        APP_PRESETS.chunked(2).forEach { pair ->
            KeyRow {
                pair.forEach { (title, id) ->
                    KeyButton(
                        onClick = { viewModel.controller.launchApp(id) },
                        modifier = Modifier.weight(1f).height(46.dp),
                        label = title,
                        labelSize = 14,
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = appId,
                onValueChange = { appId = it },
                modifier = Modifier.weight(1f),
                label = { Text("id пакета или ссылка") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { launch() }),
            )
            KeyButton(
                onClick = launch,
                modifier = Modifier.size(56.dp),
                icon = TvIcons.Send,
                contentDescription = "Запустить приложение",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ConnectDialog(
    currentHost: String,
    discovered: List<DiscoveredTv>,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
) {
    var host by remember { mutableStateOf(currentHost) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(TvIcons.Remote, contentDescription = null) },
        title = { Text("Подключение к телевизору") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP-адрес") },
                    placeholder = { Text("192.168.1.106") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { if (host.isNotBlank()) onConnect(host) },
                    ),
                )
                if (discovered.isEmpty()) {
                    Text(
                        "Ищу телевизоры в сети…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    HorizontalDivider()
                    Text("Найдены в сети:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    discovered.forEach { tv ->
                        FilledTonalButton(
                            onClick = { onConnect(tv.host) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(tv.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(tv.host, fontSize = 11.sp)
                            }
                        }
                    }
                }
                Text(
                    "Телевизор и телефон должны быть в одной сети Wi-Fi. " +
                        "При первом подключении на экране появится код спаривания.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(host) }, enabled = host.isNotBlank()) {
                Text("Подключиться")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun PinDialog(
    deviceName: String,
    retry: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(TvIcons.Tv, contentDescription = null) },
        title = { Text("Код спаривания") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (retry) {
                    Text(
                        "Код не подошёл, попробуйте ещё раз.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }
                Text("На экране «$deviceName» показан код из шести символов. Введите его:", fontSize = 13.sp)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.take(6).uppercase() },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (pin.length == 6) onSubmit(pin) },
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(pin) }, enabled = pin.length == 6) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}

private fun statusLine(state: TvState, host: String): String = when (state.status) {
    ConnectionStatus.Connected -> when (state.isOn) {
        true -> "включён · ${state.host}"
        false -> "в режиме ожидания · ${state.host}"
        null -> "подключено · ${state.host}"
    }

    ConnectionStatus.Disconnected -> if (host.isBlank()) "телевизор не выбран" else "нет связи · $host"
    ConnectionStatus.Connecting -> "подключаюсь · ${state.host.ifBlank { host }}"
    ConnectionStatus.Pairing -> "спаривание · ${state.host.ifBlank { host }}"
    ConnectionStatus.Failed -> "ошибка · ${state.host.ifBlank { host }}"
}
