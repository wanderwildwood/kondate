package com.wanderwildwood.kondate.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.widget.Toast
import com.wanderwildwood.kondate.R

/**
 * The other apps a recipe reaches: Notes for the shopping, the clock for a timer, the browser
 * for the page it came from, and anything at all for sharing. Each press that finds nothing to
 * answer it says so rather than doing nothing.
 */
object Elsewhere {

    /** Notes — oboegaki — which takes shared text with a subject as a new note of that title. */
    private const val NOTES_APP = "com.wanderwildwood.oboegaki"

    private fun notes(title: String, text: String): Intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .setPackage(NOTES_APP)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, text)

    /** Whether Notes is on the phone to take a shopping list. */
    fun hasNotes(context: Context): Boolean =
        context.packageManager.queryIntentActivities(notes("", ""), 0).isNotEmpty()

    /**
     * A shopping list to Notes as a checklist note, or, with no Notes on the phone, the same
     * list as plain text to whatever the reader picks.
     */
    fun shoppingList(context: Context, title: String, markdown: String, plain: String) {
        if (hasNotes(context) && open(context, notes(title, markdown))) return
        share(context, title, plain)
    }

    /** Text to any app that takes it, through the phone's own chooser. */
    fun share(context: Context, title: String, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, text)
        if (!open(context, Intent.createChooser(send, null))) say(context, R.string.nothing_takes_text)
    }

    /**
     * A timer started in the clock app, as Android asks any clock for one. Clock (tokei) answers
     * this; so does Android's own. [label] names it on the clock's list.
     */
    fun timer(context: Context, seconds: Int, label: String) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            // Cooking goes on here; the clock starts it and says so without opening.
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!open(context, intent)) say(context, R.string.no_clock)
    }

    /** The page a recipe came from, in the browser. */
    fun page(context: Context, url: String) {
        if (!open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) say(context, R.string.about_no_browser)
    }

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun say(context: Context, text: Int) = Toast.makeText(context, context.getString(text), Toast.LENGTH_SHORT).show()
}
