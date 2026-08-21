package com.sprit.tvremote

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sprit.tvremote.proto.remote.RemoteKeyCode
import com.sprit.tvremote.ui.RemoteScreen
import com.sprit.tvremote.ui.TvRemoteTheme

class MainActivity : ComponentActivity() {

    private val viewModel: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TvRemoteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RemoteScreen(viewModel)
                }
            }
        }
    }

    /** Пока приложение открыто, качелька громкости телефона управляет громкостью телевизора. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> RemoteKeyCode.KEYCODE_VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKeyCode.KEYCODE_VOLUME_DOWN
            else -> null
        }
        if (keyCode != null && viewModel.state.value.isConnected) {
            if (event.action == KeyEvent.ACTION_DOWN) viewModel.controller.sendKey(keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
