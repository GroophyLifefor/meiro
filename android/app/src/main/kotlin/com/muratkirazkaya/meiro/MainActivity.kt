package com.muratkirazkaya.meiro

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Seed = Color(0xFF6750A4)

class MainActivity : ComponentActivity() {
    private var ytId = "dQw4w9WgXcQ"
    private var scLink = "https://soundcloud.com/forss/flickermood"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Spike.init(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Seed)) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i?.getStringExtra("action")?.let { Spike.dispatch(it, ytId, scLink) }
    }

    @Composable
    private fun Screen() {
        var yt by remember { mutableStateOf(ytId) }
        var sc by remember { mutableStateOf(scLink) }
        ytId = yt; scLink = sc
        val list = rememberLazyListState()
        LaunchedEffect(SpikeLog.lines.size) {
            if (SpikeLog.lines.isNotEmpty()) list.scrollToItem(SpikeLog.lines.size - 1)
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(yt, { yt = it }, label = { Text("YouTube video ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sc, { sc = it }, label = { Text("SoundCloud URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ Spike.resolveYtm(yt, true) }) { Text("YTM (visionos)", fontSize = 11.sp) }
                Button({ Spike.resolveYtm(yt, false) }) { Text("YTM (default/JS)", fontSize = 11.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ Spike.resolveSoundCloud(sc) }) { Text("Resolve SoundCloud", fontSize = 11.sp) }
                Button({ Spike.update() }) { Text("Update yt-dlp", fontSize = 11.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ Spike.play("ytm") }) { Text("Play YTM", fontSize = 11.sp) }
                Button({ Spike.play("sc") }) { Text("Play SC", fontSize = 11.sp) }
                OutlinedButton({ Spike.stop() }) { Text("Stop", fontSize = 11.sp) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                items(SpikeLog.lines) { Text(it, fontSize = 10.sp, lineHeight = 12.sp) }
            }
        }
    }
}
