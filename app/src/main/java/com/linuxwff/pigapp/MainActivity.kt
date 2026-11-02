package com.linuxwff.pigapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.linuxwff.pigapp.ui.PigApp
import com.linuxwff.pigapp.ui.PigTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val requestRunCommand =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // onForeground reports the missing permission when it is still denied.
            vm.onForeground()
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The service runs either way; the notification is only hidden when denied.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val immersive = vm.ui.immersive
            LaunchedEffect(immersive) { applyImmersive(immersive) }
            PigTheme(vm.ui.uiPack) {
                PigApp(vm)
            }
        }
        if (!RelayLauncher.hasPermission(this)) {
            requestRunCommand.launch("com.termux.permission.RUN_COMMAND")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // The keep-alive service is started from onResume() based on the setting.
        // The actual connection is started from onResume() (also covers locks,
        // process freezes and returning from the background).
    }

    override fun onResume() {
        super.onResume()
        applyImmersive(vm.ui.immersive)
        vm.onForeground()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Some ROMs restore the bars after a dialog, the recents screen or an
        // orientation change; re-hide them whenever we come back to the front.
        if (hasFocus) applyImmersive(vm.ui.immersive)
    }

    private fun applyImmersive(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onPause() {
        vm.onBackground()
        super.onPause()
    }
}
