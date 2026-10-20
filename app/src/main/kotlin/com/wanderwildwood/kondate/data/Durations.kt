package com.wanderwildwood.kondate.data

/**
 * Times in a recipe: the ISO-8601 durations schema.org writes ("PT1H15M"), and the ones said in
 * the words of a step ("simmer for 10 minutes"), which is what a timer button is offered for.
 */
object Durations {

    private val ISO = Regex(
        """P(?:(\d+(?:[.,]\d+)?)Y)?(?:(\d+(?:[.,]\d+)?)M)?(?:(\d+(?:[.,]\d+)?)W)?(?:(\d+(?:[.,]\d+)?)D)?""" +
            """(?:T(?:(\d+(?:[.,]\d+)?)H)?(?:(\d+(?:[.,]\d+)?)M)?(?:(\d+(?:[.,]\d+)?)S)?)?""",
    )

    /**
     * Seconds in an ISO-8601 duration, or null when it is not one or says nothing. Years and
     * months are taken at 365 and 30 days; nobody's recipe takes either, but a page that writes
     * "P0Y0M0DT0H35M0.000S" has to read as 35 minutes. Zero is null: "PT0M" is a page saying it
     * has no figure, not a recipe that takes no time.
     */
    fun isoSeconds(text: String): Int? {
        val s = text.trim().uppercase()
        if (s.length < 2 || s[0] != 'P') return null
        val m = ISO.matchEntire(s) ?: return null
        if (m.groupValues.drop(1).all { it.isEmpty() }) return null
        val per = doubleArrayOf(365.0 * 86400, 30.0 * 86400, 7.0 * 86400, 86400.0, 3600.0, 60.0, 1.0)
        var total = 0.0
        for (i in per.indices) {
            val g = m.groupValues[i + 1]
            if (g.isNotEmpty()) total += g.replace(',', '.').toDouble() * per[i]
        }
        val seconds = Math.round(total).toInt()
        return seconds.takeIf { it > 0 }
    }

    /** Minutes written as an ISO-8601 duration, the way the editor stores what is typed. */
    fun isoOfMinutes(minutes: Int): String {
        if (minutes <= 0) return ""
        val h = minutes / 60
        val m = minutes % 60
        return "PT" + (if (h > 0) "${h}H" else "") + (if (m > 0) "${m}M" else "")
    }

    /** Whole minutes in an ISO-8601 duration, for the editor, or null. */
    fun minutesOf(iso: String): Int? = isoSeconds(iso)?.let { (it + 30) / 60 }?.takeIf { it > 0 }

    /** A length of time found in the words of a step: how long, and the words that said it. */
    data class Found(val seconds: Int, val words: String)

    private const val NUMBER =
        """\d+\s+\d+/\d+|\d+\s*[½¼¾⅓⅔]|[½¼¾⅓⅔]|\d+/\d+|\d+(?:[.,]\d+)?|""" +
            """an|a|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty-five|forty|sixty|ninety"""

    private const val UNIT = """hours?|hrs?|minutes?|mins?|seconds?|secs?"""

    /**
     * One length: a number, perhaps a range ("10-12", "10 to 12": the first is taken, which is
     * when to look), and a unit. "1 1/2 hours", "½ hour", "an hour", "a half hour" and "10m" too,
     * the single letters only when they touch the figure, so "5 m of string" stays out of it.
     */
    private val ONE = Regex(
        """(?i)\b(?:(half\s+an?|a\s+half)\s+(hour)|($NUMBER)(?:\s*(?:-|–|—|to|or)\s*(?:$NUMBER))?(?:\s*($UNIT)\b|([hms])\b))""" +
            """(?:\s+and\s+a\s+half\b)?""",
    )

    /** What may come between two lengths that are one time: "1 hour 15 minutes", "1 hr and 15 min". */
    private val JOIN = Regex("""^\s*(?:,|and|,\s*and)?\s*$""", RegexOption.IGNORE_CASE)

    /**
     * Every length of time a step's words give, in order, each once. Two lengths next to each
     * other in larger then smaller units are one time: "1 hour 15 minutes" is 75 minutes, not
     * an hour and a quarter of an hour. English only, as the words matched are English.
     */
    fun inText(text: String): List<Found> {
        val parts = ONE.findAll(text).mapNotNull { m -> secondsOf(m)?.let { Triple(m.range, it, m.value) } }.toList()
        val out = mutableListOf<Found>()
        var i = 0
        while (i < parts.size) {
            var (range, seconds, _) = parts[i]
            var unitSize = unitSecondsOf(parts[i].third)
            var end = range.last
            var j = i + 1
            while (j < parts.size) {
                val gap = text.substring(end + 1, parts[j].first.first)
                val next = unitSecondsOf(parts[j].third)
                if (!JOIN.matches(gap) || next >= unitSize) break
                seconds += parts[j].second
                unitSize = next
                end = parts[j].first.last
                j++
            }
            val words = text.substring(range.first, end + 1).trim()
            if (seconds in 1..24 * 3600 && out.none { it.seconds == seconds }) out += Found(seconds, words)
            i = j
        }
        return out
    }

    private fun secondsOf(m: MatchResult): Int? {
        val g = m.groupValues
        if (g[1].isNotEmpty()) return 1800
        val amount = amountOf(g[3]) ?: return null
        val unit = (g[4].ifEmpty { g[5] }).lowercase()
        val per = when {
            unit.startsWith("h") -> 3600
            unit.startsWith("m") -> 60
            unit.startsWith("s") -> 1
            else -> return null
        }
        val half = if (m.value.lowercase().contains("and a half")) 0.5 else 0.0
        return Math.round((amount + half) * per).toInt()
    }

    /** The size of the unit a matched length is in, so "1 hour 15 minutes" is seen to run downwards. */
    private fun unitSecondsOf(words: String): Int {
        val w = words.lowercase()
        return when {
            Regex("""h(ou)?rs?\b|\dh\b|hour""").containsMatchIn(w) -> 3600
            Regex("""min|\dm\b""").containsMatchIn(w) -> 60
            else -> 1
        }
    }

    private val WORDS = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0,
        "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0,
        "twelve" to 12.0, "fifteen" to 15.0, "twenty" to 20.0, "thirty" to 30.0, "forty" to 40.0,
        "forty-five" to 45.0, "sixty" to 60.0, "ninety" to 90.0,
    )

    /** A number as a recipe writes it: 10, 1.5, 1,5, 1/2, 1 1/2, ½, 1½, or a word. */
    internal fun amountOf(text: String): Double? {
        val t = text.trim().lowercase()
        WORDS[t]?.let { return it }
        return Quantities.parse(t)
    }
}
