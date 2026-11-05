package com.example.porticho

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private val controller get() = (application as RolaApp).controller

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { RolaApp(controller) }
    }
}

@Composable
fun RolaApp(c: RolaController) {
    val scheme = if (isSystemInDarkTheme())
        darkColorScheme(
            primary = Color(0xFF8C97FF), onPrimary = Color(0xFF0F1326),
            background = Color(0xFF0F1326), surface = Color(0xFF181D38),
            onBackground = Color(0xFFEEF0FA), onSurface = Color(0xFFEEF0FA), outlineVariant = Color(0xFF2A3157)
        )
    else
        lightColorScheme(
            primary = Color(0xFF3B4BF2), onPrimary = Color.White,
            background = Color(0xFFEDEFF5), surface = Color.White,
            onBackground = Color(0xFF151B36), onSurface = Color(0xFF151B36), outlineVariant = Color(0xFFD5D9E8)
        )
    MaterialTheme(colorScheme = scheme) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { Screen(c) }
    }
}

@Composable
fun Screen(c: RolaController) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        // Reproductor oculto: solo se usa el audio.
        Box(Modifier.size(0.dp)) {
            AndroidView(
                factory = { _ -> c.player.web.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.requiredSize(200.dp).alpha(0f)
            )
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp)) {
            item { Text("Rola", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold) }
            item { Spacer(Modifier.height(14.dp)); NowPlaying(c) }
            item { Spacer(Modifier.height(20.dp)); AddBar(c) }
            item { Spacer(Modifier.height(18.dp)); Text("Cola", fontWeight = FontWeight.SemiBold) }
            if (c.queue.isEmpty()) item {
                Text("La cola está vacía. Pega un enlace para empezar.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f), modifier = Modifier.padding(vertical = 14.dp))
            }
            itemsIndexed(c.queue) { i, item -> QueueRow(c, i, item) }
        }
    }
}

private fun fmt(t: Float): String { val s = t.toInt(); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
private fun thumb(v: String) = "https://i.ytimg.com/vi/$v/mqdefault.jpg"

@Composable
fun NowPlaying(c: RolaController) {
    val cs = MaterialTheme.colorScheme
    val cur = c.queue.getOrNull(c.idx)
    val v = cur?.vid()
    val m = v?.let { c.meta[it] }
    val title = when {
        cur == null -> "Nada sonando"
        m != null -> m.title
        cur.isList -> "Lista de reproducción"
        else -> "Video ${cur.id}"
    }
    val sub = when { cur == null -> "Agrega un enlace de YouTube"; m != null -> m.author; else -> "YouTube" }
    val spin by rememberInfiniteTransition(label = "spin")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val groove = cs.surface.copy(alpha = .35f)
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur = max(c.duration, 1f)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(230.dp).rotate(if (c.playing) spin else 0f).clip(CircleShape).background(cs.onBackground),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                var r = size.minDimension / 2 - 4f
                while (r > size.minDimension * 0.3f) { drawCircle(groove, r, style = Stroke(1.5f)); r -= 7f }
            }
            Box(Modifier.size(92.dp).clip(CircleShape).background(cs.primary)) {
                if (v != null) AsyncImage(thumb(v), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(cs.background))
        }
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Text(sub, color = cs.onBackground.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(drag ?: c.position), style = MaterialTheme.typography.labelSmall)
            Slider(
                value = (drag ?: c.position).coerceIn(0f, dur), valueRange = 0f..dur,
                onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { c.seek(it) }; drag = null },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text(fmt(c.duration), style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconButton(onClick = { c.prev() }) { Icon(Icons.Filled.SkipPrevious, "Anterior") }
            FilledIconButton(onClick = { c.toggle() }, modifier = Modifier.size(64.dp)) {
                Icon(if (c.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (c.playing) "Pausar" else "Reproducir", Modifier.size(30.dp))
            }
            IconButton(onClick = { c.next() }) { Icon(Icons.Filled.SkipNext, "Siguiente") }
        }
    }
}

@Composable
fun AddBar(c: RolaController) {
    var text by remember { mutableStateOf("") }
    Column {
        Text("Agregar canciones o listas", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Pega enlaces de YouTube (videos o listas)") }
            )
            Button(onClick = { c.add(text); text = "" }) { Text("Agregar") }
        }
        if (c.hint.isNotEmpty()) Text(c.hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun QueueRow(c: RolaController, i: Int, item: Item) {
    val cs = MaterialTheme.colorScheme
    val cur = i == c.idx
    val v = item.vid()
    val m = v?.let { c.meta[it] }
    val name = if (item.isList) "Lista de reproducción" else m?.title ?: "Video ${item.id}"
    val sub = if (item.isList) (if (item.tracks.isEmpty()) "Leyendo canciones…" else "${item.tracks.size} canciones") else m?.author.orEmpty()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp, 42.dp).clip(RoundedCornerShape(6.dp)).background(cs.outlineVariant)) {
                if (v != null) AsyncImage(thumb(v), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f).clickable { c.select(i) }.padding(horizontal = 10.dp)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (cur) cs.primary else cs.onBackground,
                    fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal)
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onBackground.copy(alpha = .6f))
            }
            IconButton(onClick = { c.remove(i) }) { Icon(Icons.Filled.Close, "Quitar") }
        }
        item.tracks.forEachIndexed { j, tv ->
            val on = cur && j == item.pi
            Text(
                "${j + 1}   ${c.trackName(tv, j + 1)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (on) cs.primary else cs.onBackground.copy(alpha = .65f),
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.fillMaxWidth().clickable { c.playTrack(i, j) }.padding(start = 66.dp, top = 6.dp, bottom = 6.dp)
            )
        }
    }
}