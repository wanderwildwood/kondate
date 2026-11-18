package com.wanderwildwood.kondate.ui

import androidx.compose.runtime.saveable.Saver
import com.wanderwildwood.kondate.data.Recipe
import org.json.JSONObject

/**
 * The stack of places, kept through the system stopping the app in the background: a recipe
 * half typed in comes back as it was, draft and all, instead of the list. Each place is one
 * line of JSON; a draft goes in the shape the recipes themselves are kept in.
 */
object ScreenStack {

    val saver: Saver<List<Screen>, ArrayList<String>> = Saver(
        save = { stack -> ArrayList(stack.map { write(it) }) },
        restore = { lines -> lines.mapNotNull { read(it) }.ifEmpty { listOf(Screen.List) } },
    )

    fun write(s: Screen): String {
        val o = JSONObject()
        when (s) {
            Screen.List -> o.put("at", "list")
            is Screen.Recipe -> o.put("at", "recipe").put("id", s.id)
            is Screen.Edit -> {
                o.put("at", "edit").put("note", s.note)
                s.id?.let { o.put("id", it) }
                s.draft?.let { o.put("draft", it.toJson()) }
            }
            is Screen.Cook -> o.put("at", "cook").put("id", s.id)
            is Screen.Reading -> o.put("at", "reading").put("url", s.url)
            Screen.Paste -> o.put("at", "paste")
            Screen.Settings -> o.put("at", "settings")
            Screen.Nextcloud -> o.put("at", "nextcloud")
        }
        return o.toString()
    }

    fun read(line: String): Screen? = runCatching {
        val o = JSONObject(line)
        when (o.getString("at")) {
            "list" -> Screen.List
            "recipe" -> Screen.Recipe(o.getString("id"))
            "edit" -> Screen.Edit(
                id = if (o.has("id")) o.getString("id") else null,
                draft = o.optJSONObject("draft")?.let { Recipe.fromOwnJson(it.toString()) },
                note = o.optInt("note"),
            )
            "cook" -> Screen.Cook(o.getString("id"))
            "reading" -> Screen.Reading(o.getString("url"))
            "paste" -> Screen.Paste
            "settings" -> Screen.Settings
            "nextcloud" -> Screen.Nextcloud
            else -> null
        }
    }.getOrNull()
}
