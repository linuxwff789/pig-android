package com.linuxwff.pigapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.linuxwff.pigapp.ui.PigApp
import com.linuxwff.pigapp.ui.PigTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val requestRunCommand =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // connect() reports the missing permission when it is still denied.
            vm.connect()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            PigTheme(vm.ui.uiPack) {
                PigApp(vm)
            }
        }
        if (RelayLauncher.hasPermission(this)) {
            vm.connect()
        } else {
            requestRunCommand.launch("com.termux.permission.RUN_COMMAND")
        }
    }
}
