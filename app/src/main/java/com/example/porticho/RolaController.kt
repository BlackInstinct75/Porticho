package com.example.porticho

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat

class RolaController(ctx: Context) {
    private val app = ctx.applicationContext
    private val store = Store(ctx)
    private val h = Handler(Looper.getMainLooper())

    val queue = mutableStateListOf<Item>()
    val meta = mutableStateMapOf<String, Meta>()
    var idx by mutableIntStateOf(0)
    var playing by mutableStateOf(false)
    var position by mutableFloatStateOf(0f)
    var duration by mutableFloatStateOf(0f)
    var hint by mutableStateOf("")
    var showVideo by mutableStateOf(false)

    /** El servicio de reproducción se suscribe aquí para actualizar la notificación. */
    var listener: (() -> Unit)? = null

    private var loaded = false
    private val probeQ = ArrayDeque<String>()
    private var probeReady = false
    private var probeBusy = false
    private var probeCurrent: String? = null
    private var lastProbe: List<String> = emptyList()
    private val timeout = Runnable { probeNext() }

    val player = YtView(ctx, object : YtView.Callbacks {
        override fun onReady() {}
        override fun onInfo(i: YtView.Info) = handleInfo(i)
        override fun onState(s: Int) = handleState(s)
        override fun onTime(cur: Double, dur: Double) { position = cur.toFloat(); duration = dur.toFloat() }
        override fun onError(code: Int) { hint = "YouTube no permite reproducir este video aquí (código $code)." }
    })

    /** WebView oculto que solo lee el contenido de las listas, sin reproducir. */
    val probe = YtView(ctx, object : YtView.Callbacks {
        override fun onReady() { probeReady = true; probeNext() }
        override fun onInfo(i: YtView.Info) = handleProbe(i)
        override fun onState(s: Int) {}
        override fun onTime(cur: Double, dur: Double) {}
        override fun onError(code: Int) {}
    })

    init {
        val (q, m, i) = store.load()
        queue.addAll(q); meta.putAll(m); idx = i.coerceIn(0, maxOf(q.size - 1, 0))
        queue.forEach { enqueueProbe(it) }
    }

    fun trackName(v: String, n: Int) = meta[v]?.title ?: "Canción $n"

    private fun persist() = store.save(queue.toList(), meta.toMap(), idx)

    private fun handleInfo(i: YtView.Info) {
        if (i.id.isNotEmpty() && i.title.isNotEmpty() && meta[i.id]?.title != i.title) meta[i.id] = Meta(i.title, i.author)
        val cur = queue.getOrNull(idx)
        if (cur != null && cur.isList) {
            var n = cur
            if (i.playlist.isNotEmpty() && i.playlist != cur.tracks) n = n.copy(tracks = i.playlist)
            if (i.index >= 0 && i.index != n.pi) n = n.copy(pi = i.index)
            if (n != cur) queue[idx] = n
        }
        persist()
        listener?.invoke()
    }

    private fun handleState(s: Int) {
        when (s) {
            1 -> playing = true
            2 -> playing = false
            0 -> {
                playing = false
                val cur = queue.getOrNull(idx)
                // En una lista, YouTube avanza solo entre canciones; solo pasamos al siguiente elemento al terminar la última.
                if (cur == null || !cur.isList || cur.pi >= cur.tracks.size - 1) next(auto = true)
            }
        }
        listener?.invoke()
    }

    private fun handleProbe(i: YtView.Info) {
        val id = probeCurrent ?: return
        if (i.id.isNotEmpty() && i.title.isNotEmpty() && meta[i.id] == null) meta[i.id] = Meta(i.title, i.author)
        if (i.playlist.isEmpty() || i.playlist == lastProbe) return
        lastProbe = i.playlist
        queue.forEachIndexed { k, it -> if (it.list == id && it.tracks.isEmpty()) queue[k] = it.copy(tracks = i.playlist) }
        persist()
        probeNext()
    }

    private fun enqueueProbe(it: Item) {
        if (it.isList && it.tracks.isEmpty() && it.list !in probeQ) probeQ.addLast(it.list)
        if (probeReady && !probeBusy) probeNext()
    }

    private fun probeNext() {
        h.removeCallbacks(timeout)
        probeCurrent = probeQ.removeFirstOrNull()
        val id = probeCurrent
        probeBusy = id != null
        if (id != null) {
            probe.js("cueList('$id')")
            h.postDelayed(timeout, 8000)
        }
    }

    fun add(text: String) {
        val items = parseLinks(text)
        if (items.isEmpty()) { hint = "No encontré enlaces de YouTube válidos."; return }
        val was = queue.isEmpty()
        queue.addAll(items)
        if (was) idx = 0
        hint = if (items.size == 1) "1 elemento agregado." else "${items.size} elementos agregados."
        items.forEach { enqueueProbe(it) }
        persist()
    }

    fun remove(i: Int) {
        if (i !in queue.indices) return
        queue.removeAt(i)
        if (i < idx) idx-- else if (i == idx) {
            loaded = false; playing = false; player.js("pause()")
            idx = idx.coerceIn(0, maxOf(queue.size - 1, 0))
        }
        persist()
    }

    fun load(i: Int, start: Int = 0) {
        val it = queue.getOrNull(i) ?: return
        idx = i; loaded = true; hint = ""; position = 0f; duration = 0f
        if (it.isList) { queue[i] = it.copy(pi = start); player.js("loadList('${it.list}',$start)") }
        else player.js("loadVideo('${it.id}')")
        persist()
        ensureService()
        listener?.invoke()
    }

    fun select(i: Int) {
        val it = queue.getOrNull(i) ?: return
        if (i == idx && loaded && !it.isList) return
        load(i, it.pi)
    }

    fun playTrack(i: Int, j: Int) {
        val it = queue.getOrNull(i) ?: return
        if (i == idx && loaded) { queue[i] = it.copy(pi = j); player.js("playAt($j)") } else load(i, j)
    }

    fun toggle() {
        if (queue.isEmpty()) { hint = "Primero agrega al menos un enlace."; return }
        if (!loaded) load(idx, queue[idx].pi) else { if (!playing) ensureService(); player.js(if (playing) "pause()" else "play()") }
    }

    fun next(auto: Boolean = false) {
        val cur = queue.getOrNull(idx) ?: return
        if (cur.isList && loaded && !auto) { player.js("nextV()"); return }
        val n = idx + 1
        if (n < queue.size) load(n) else if (!auto) load(0)
    }

    fun prev() {
        val cur = queue.getOrNull(idx) ?: return
        if (cur.isList && loaded) { player.js("prevV()"); return }
        load(maxOf(idx - 1, 0))
    }

    fun seek(s: Float) { position = s; player.js("seek($s)") }
    fun setVolume(v: Int) = player.js("vol($v)")

    private fun ensureService() {
        try { ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java)) } catch (e: Exception) { hint = "No se pudo iniciar la reproducción en segundo plano." }
    }

    /** Pausa sin avisar al servicio (se usa al cerrarlo desde la notificación). */
    fun stopPlayback() { if (playing) player.js("pause()"); playing = false }

    fun release() { player.web.destroy(); probe.web.destroy() }
}