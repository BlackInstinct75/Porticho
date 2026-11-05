package com.example.porticho

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Item(
    val id: String = "",
    val list: String = "",
    val tracks: List<String> = emptyList(),
    val pi: Int = 0
) {
    val isList get() = list.isNotEmpty()
    fun vid(): String? = if (isList) tracks.getOrNull(pi) else id
}

data class Meta(val title: String, val author: String)

private val VIDEO = Regex("""(?:v=|youtu\.be/|shorts/|embed/|live/)([\w-]{11})""")
private val BARE = Regex("""^[\w-]{11}$""")
private val LIST = Regex("""[?&]list=([\w-]+)""")
private val HAS_V = Regex("""[?&]v=""")

fun parseLinks(text: String): List<Item> =
    text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.mapNotNull { p ->
        val l = LIST.find(p)
        val v = VIDEO.find(p)?.groupValues?.get(1) ?: BARE.find(p)?.value
        when {
            l != null && !HAS_V.containsMatchIn(p) -> Item(list = l.groupValues[1])
            v != null -> Item(id = v)
            else -> null
        }
    }

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("rola", Context.MODE_PRIVATE)

    fun save(q: List<Item>, meta: Map<String, Meta>, idx: Int) {
        val o = JSONObject()
        o.put("idx", idx)
        o.put("q", JSONArray().also { a ->
            q.forEach {
                a.put(JSONObject().put("id", it.id).put("list", it.list).put("pi", it.pi)
                    .put("tracks", JSONArray(it.tracks)))
            }
        })
        o.put("t", JSONObject().also { t ->
            meta.forEach { (k, m) -> t.put(k, JSONObject().put("title", m.title).put("author", m.author)) }
        })
        sp.edit().putString("state", o.toString()).apply()
    }

    fun load(): Triple<List<Item>, Map<String, Meta>, Int> = try {
        val o = JSONObject(sp.getString("state", null) ?: "{}")
        val a = o.optJSONArray("q") ?: JSONArray()
        val q = List(a.length()) {
            val j = a.getJSONObject(it)
            val tr = j.optJSONArray("tracks") ?: JSONArray()
            Item(j.optString("id"), j.optString("list"), List(tr.length()) { k -> tr.getString(k) }, j.optInt("pi"))
        }
        val t = o.optJSONObject("t") ?: JSONObject()
        val meta = t.keys().asSequence().associateWith {
            val m = t.getJSONObject(it); Meta(m.optString("title"), m.optString("author"))
        }
        Triple(q, meta, o.optInt("idx"))
    } catch (e: Exception) {
        Triple(emptyList(), emptyMap(), 0)
    }
}
