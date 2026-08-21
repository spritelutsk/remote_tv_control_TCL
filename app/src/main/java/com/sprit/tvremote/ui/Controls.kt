package com.sprit.tvremote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Кнопка пульта.
 *
 * Обычная кнопка срабатывает в момент нажатия — так пульт ощущается отзывчивым; при
 * [repeatable] удержание повторяет команду. Если задан [onLongClick], команда уходит по
 * отпусканию: иначе короткое нажатие было бы не отличить от долгого.
 */
@Composable
fun KeyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    label: String? = null,
    caption: String? = null,
    contentDescription: String? = null,
    shape: Shape = CircleShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    labelSize: Int = 17,
    iconSize: Int = 24,
    repeatable: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onHoldStart: (() -> Unit)? = null,
    onHoldEnd: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = if (pressed) MaterialTheme.colorScheme.primaryContainer else containerColor,
        label = "key-background",
    )

    Surface(
        modifier = modifier.pointerInput(repeatable, onLongClick != null, onHoldStart != null) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    var repeat: Job? = null
                    when {
                        // Кнопка «нажми и держи»: команда живёт ровно пока палец на экране.
                        onHoldStart != null -> onHoldStart()
                        onLongClick == null -> {
                            onClick()
                            if (repeatable) repeat = scope.autoRepeat(onClick)
                        }
                    }
                    tryAwaitRelease()
                    repeat?.cancel()
                    onHoldEnd?.invoke()
                    pressed = false
                },
                onTap = { if (onLongClick != null && onHoldStart == null) onClick() },
                onLongPress = { if (onHoldStart == null) onLongClick?.invoke() },
            )
        },
        shape = shape,
        color = background,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                icon != null -> Icon(icon, contentDescription ?: label ?: caption, Modifier.size(iconSize.dp))
                label != null -> Text(
                    label,
                    fontSize = labelSize.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
            if (caption != null) {
                Text(
                    caption,
                    fontSize = 10.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun CoroutineScope.autoRepeat(action: () -> Unit): Job = launch {
    delay(FIRST_REPEAT_MS)
    while (isActive) {
        action()
        delay(NEXT_REPEAT_MS)
    }
}

private const val FIRST_REPEAT_MS = 420L
private const val NEXT_REPEAT_MS = 130L

/** Круг навигации: четыре стрелки по краям и OK в центре (долгое нажатие — контекстное меню). */
@Composable
fun DirectionPad(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onOk: () -> Unit,
    onOkLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val side = minOf(maxWidth, maxHeight)
        val arrow = side * 0.32f
        val center = side * 0.36f

        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )

        KeyButton(
            onClick = onUp,
            modifier = Modifier.size(arrow).align(Alignment.TopCenter),
            icon = TvIcons.Up,
            contentDescription = "Вверх",
            containerColor = Color.Transparent,
            iconSize = 32,
            repeatable = true,
        )
        KeyButton(
            onClick = onDown,
            modifier = Modifier.size(arrow).align(Alignment.BottomCenter),
            icon = TvIcons.Down,
            contentDescription = "Вниз",
            containerColor = Color.Transparent,
            iconSize = 32,
            repeatable = true,
        )
        KeyButton(
            onClick = onLeft,
            modifier = Modifier.size(arrow).align(Alignment.CenterStart),
            icon = TvIcons.Left,
            contentDescription = "Влево",
            containerColor = Color.Transparent,
            iconSize = 32,
            repeatable = true,
        )
        KeyButton(
            onClick = onRight,
            modifier = Modifier.size(arrow).align(Alignment.CenterEnd),
            icon = TvIcons.Right,
            contentDescription = "Вправо",
            containerColor = Color.Transparent,
            iconSize = 32,
            repeatable = true,
        )
        KeyButton(
            onClick = onOk,
            onLongClick = onOkLongClick,
            modifier = Modifier.size(center).align(Alignment.Center),
            label = "OK",
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Вертикальная качелька: «больше» сверху, «меньше» снизу, подпись между ними. */
@Composable
fun Rocker(
    caption: String,
    topIcon: ImageVector,
    bottomIcon: ImageVector,
    onTop: () -> Unit,
    onBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 45),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            KeyButton(
                onClick = onTop,
                modifier = Modifier.size(54.dp),
                icon = topIcon,
                contentDescription = "$caption больше",
                containerColor = Color.Transparent,
                iconSize = 26,
                repeatable = true,
            )
            Text(
                caption,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyButton(
                onClick = onBottom,
                modifier = Modifier.size(54.dp),
                icon = bottomIcon,
                contentDescription = "$caption меньше",
                containerColor = Color.Transparent,
                iconSize = 26,
                repeatable = true,
            )
        }
    }
}

/** Ряд равных по ширине кнопок. */
@Composable
fun KeyRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
