package com.sprit.tvremote.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi

/** Иконки пульта в одном месте — чтобы экран не тонул в импортах. */
object TvIcons {
    val Up = Icons.Rounded.KeyboardArrowUp
    val Down = Icons.Rounded.KeyboardArrowDown
    val Left = Icons.AutoMirrored.Rounded.KeyboardArrowLeft
    val Right = Icons.AutoMirrored.Rounded.KeyboardArrowRight

    val Power = Icons.Rounded.PowerSettingsNew
    val Home = Icons.Rounded.Home
    val Back = Icons.AutoMirrored.Rounded.ArrowBack
    val Menu = Icons.Rounded.Menu

    val VolumeUp = Icons.AutoMirrored.Rounded.VolumeUp
    val Mute = Icons.AutoMirrored.Rounded.VolumeOff
    val Plus = Icons.Rounded.Add
    val Minus = Icons.Rounded.Remove

    val Play = Icons.Rounded.PlayArrow
    val Stop = Icons.Rounded.Stop
    val Next = Icons.Rounded.SkipNext
    val Previous = Icons.Rounded.SkipPrevious
    val Forward = Icons.Rounded.FastForward
    val Rewind = Icons.Rounded.FastRewind

    val Search = Icons.Rounded.Search
    val Assistant = Icons.Rounded.Mic
    val Settings = Icons.Rounded.Settings
    val Source = Icons.AutoMirrored.Rounded.Input
    val Info = Icons.Rounded.Info

    val Keyboard = Icons.Rounded.Keyboard
    val Numbers = Icons.Rounded.Dialpad
    val Apps = Icons.Rounded.Apps
    val Send = Icons.AutoMirrored.Rounded.Send

    val Tv = Icons.Rounded.Tv
    val Wifi = Icons.Rounded.Wifi
    val Remote = Icons.Rounded.SettingsRemote
    val More = Icons.Rounded.MoreVert
}
