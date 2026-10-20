package com.wanderwildwood.kondate.ui

import android.content.Context
import com.wanderwildwood.kondate.R
import com.wanderwildwood.kondate.data.Durations
import com.wanderwildwood.kondate.data.Quantities
import com.wanderwildwood.kondate.data.Recipe

/** How the app says a time, a yield and a scale, from its strings. */
object Words {

    /** A length of time the way a cook says it: "40 min", "1 hr 15 min", "2 hr", "30 sec". */
    fun length(context: Context, seconds: Int): String {
        if (seconds < 60) return context.getString(R.string.time_seconds, seconds)
        val minutes = (seconds + 30) / 60
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> context.getString(R.string.time_minutes, m)
            m == 0 -> context.getString(R.string.time_hours, h)
            else -> context.getString(R.string.time_hours_minutes, h, m)
        }
    }

    /** A time a recipe gives: an ISO-8601 duration said in words, or what the page wrote, as it wrote it. */
    fun time(context: Context, value: String): String =
        Durations.isoSeconds(value)?.let { length(context, it) } ?: value

    /** "Prep 15 min · Cook 40 min · Total 55 min", leaving out what the recipe does not say. */
    fun times(context: Context, r: Recipe): String = listOfNotNull(
        r.prepTime.takeIf { it.isNotBlank() }?.let { context.getString(R.string.prep_time, time(context, it)) },
        r.cookTime.takeIf { it.isNotBlank() }?.let { context.getString(R.string.cook_time, time(context, it)) },
        r.totalTime.takeIf { it.isNotBlank() }?.let { context.getString(R.string.total_time, time(context, it)) },
    ).joinToString(" · ")

    /** The line under a recipe's name in the list: how many it serves and how long it takes. */
    fun summary(context: Context, r: Recipe): String {
        val serves = r.servings?.let { yieldOf(context, r.recipeYield, it) }
        val total = Durations.isoSeconds(r.totalTime)
            ?: listOfNotNull(Durations.isoSeconds(r.prepTime), Durations.isoSeconds(r.cookTime)).takeIf { it.isNotEmpty() }?.sum()
        return listOfNotNull(serves, total?.let { length(context, it) }).joinToString(" · ")
    }

    /**
     * The yield at [count]: "Serves 6" for a yield that counts people or is a bare number, and
     * the yield's own words with the number changed for one that counts something else, so
     * "8 pancakes" made larger reads "12 pancakes" rather than "Serves 12".
     */
    fun yieldOf(context: Context, text: String, count: Int): String {
        val t = text.trim()
        val people = t.all { it.isDigit() } || PEOPLE.containsMatchIn(t)
        return if (people) context.resources.getQuantityString(R.plurals.serves, count, count)
        else t.replaceFirst(Regex("""\d+(\s*[-–]\s*\d+)?"""), count.toString())
    }

    private val PEOPLE = Regex("(?i)serv|people|person|portion|helping")

    /** "×1 1/2", for a recipe that does not say how many it serves. */
    fun factor(context: Context, factor: Double): String = context.getString(R.string.scale_factor, Quantities.write(factor))
}
