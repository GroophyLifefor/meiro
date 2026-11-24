package com.muratkirazkaya.meiro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.muratkirazkaya.meiro.playback.PlaybackHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import com.muratkirazkaya.meiro.ui.MeiroApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MeiroApplication
        // A sync every time the app comes to the foreground (and on start).
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lifecycleScope.launch { app.container().sync("foreground") }
            }
        })
        // The media and download notifications need the notification permission (Android 13+): asked once, when
        // something first plays or a download is first asked for (M6e).
        val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        lifecycleScope.launch {
            val downloads = app.container().downloader.state
            merge(PlaybackHost.ui.map { it != null }, downloads.map { it.isNotEmpty() }).first { it }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MeiroApp(app) }
    }
}
