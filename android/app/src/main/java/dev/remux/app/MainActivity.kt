package dev.remux.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import dev.remux.app.ui.MainViewModel
import dev.remux.app.ui.RemoteMuxApp
import dev.remux.app.ui.theme.RemoteMuxTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoteMuxTheme {
                RemoteMuxApp(viewModel)
            }
        }
    }
}
