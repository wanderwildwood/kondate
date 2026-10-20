package com.wanderwildwood.kondate.data

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Amounts at the head of an ingredient line, and the same line for more or fewer people.
 *
 * Only a number the line starts with is changed — "2 cups flour", "1 1/2 tsp salt", "½ onion",
 * "400g tomatoes", "2-3 cloves garlic". Anything else is left as written: "salt to taste" has
 * nothing to scale, and a number in the middle of a line ("1 can (400 g) tomatoes") is more often
 * a size than an amount, so only the 1 changes. Scaling is arithmetic, not cooking: three eggs
 * for one and a half servings comes out as 1 1/2, and the cook decides.
 */
object Quantities {

    private val VULGAR = mapOf(
        '½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3,
        '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875,
        '⅕' to 0.2, '⅖' to 0.4, '⅗' to 0.6, '⅘' to 0.8, '⅙' to 1.0 / 6, '⅚' to 5.0 / 6,
    )
    private const val V = "½¼¾⅓⅔⅛⅜⅝⅞⅕⅖⅗⅘⅙⅚"

    /** One amount: 1 1/2, 1½, ½, 3/4, 1.5, 1,5, 2. The fraction slash ⁄ counts as /. */
    private const val AMOUNT = """\d+\s+\d+\s*[/⁄]\s*\d+|\d+\s*[$V]|[$V]|\d+\s*[/⁄]\s*\d+|\d+(?:[.,]\d+)?"""

    /** A line's head: an optional bullet, an amount, and perhaps a range to a second one. */
    private val HEAD = Regex("""^(\s*(?:[-•*]\s+)?)($AMOUNT)(?:(\s*(?:-|–|—|to)\s*)($AMOUNT))?""")

    /** The value of one amount as written, or null. */
    fun parse(text: String): Double? {
        val t = text.trim().replace('⁄', '/')
        if (t.isEmpty()) return null
        Regex("""^(\d+)\s+(\d+)\s*/\s*(\d+)$""").matchEntire(t)?.let { m ->
            val d = m.groupValues[3].toDouble()
            return if (d == 0.0) null else m.groupValues[1].toDouble() + m.groupValues[2].toDouble() / d
        }
        Regex("""^(\d+)?\s*([$V])$""").matchEntire(t)?.let { m ->
            return (m.groupValues[1].toDoubleOrNull() ?: 0.0) + VULGAR.getValue(m.groupValues[2][0])
        }
        Regex("""^(\d+)\s*/\s*(\d+)$""").matchEntire(t)?.let { m ->
            val d = m.groupValues[2].toDouble()
            return if (d == 0.0) null else m.groupValues[1].toDouble() / d
        }
        Regex("""^\d+(?:[.,]\d+)?$""").matchEntire(t)?.let { return t.replace(',', '.').toDouble() }
        return null
    }

    /**
     * [line] with the amount at its head multiplied by [factor], written the way the line wrote
     * it: a fraction stays a fraction where one fits, a decimal stays a decimal. A line with no
     * amount at its head comes back as it was.
     */
    fun scale(line: String, factor: Double): String {
        if (abs(factor - 1.0) < 1e-9) return line
        val m = HEAD.find(line) ?: return line
        val first = m.groups[2]!!.value
        // "1,000 g" is a thousand, not one; a figure written with a thousands comma is left alone.
        if (Regex("""^\d{1,3},\d{3}$""").matches(first)) return line
        val a = parse(first) ?: return line
        val sep = m.groups[3]?.value
        val second = m.groups[4]?.value
        val b = if (sep != null && second != null) parse(second) else null
        // A figure that runs on past a comma ("1,250,000") is not one this can read; leave it.
        val after = line.substring(m.range.last + 1)
        if (after.startsWith(",") && after.drop(1).firstOrNull()?.isDigit() == true) return line
        val decimal = first.contains('.') || first.contains(',')
        val head = m.groups[1]!!.value
        val scaled = if (sep != null && b != null) {
            write(a * factor, decimal) + sep + write(b * factor, decimal)
        } else {
            write(a * factor, decimal)
        }
        val end = if (b != null) m.range.last + 1 else m.groups[2]!!.range.last + 1
        val rest = line.substring(end)
        // "1½cups" had the unit touching; a written amount that now ends in a fraction needs a space.
        val gap = if (rest.isNotEmpty() && rest[0].isLetter() && scaled.contains('/') && first.last() in V) " " else ""
        return head + scaled + gap + rest
    }

    private val FRACTIONS = listOf(
        1.0 / 8 to "1/8", 1.0 / 4 to "1/4", 1.0 / 3 to "1/3", 3.0 / 8 to "3/8", 1.0 / 2 to "1/2",
        5.0 / 8 to "5/8", 2.0 / 3 to "2/3", 3.0 / 4 to "3/4", 7.0 / 8 to "7/8",
    )

    /**
     * An amount written for a cook: a whole number when it is one (within a hair), else a whole
     * number and a kitchen fraction (1/8 to 7/8, thirds) when one is close, else up to two
     * decimal places. [decimal] keeps the decimal form for a line that used it ("1.5 kg").
     * Twenty and over is rounded to a whole number: nobody weighs 187.5 g of flour.
     */
    fun write(value: Double, decimal: Boolean = false): String {
        if (value >= 20) return value.roundToLong().toString()
        val whole = floor(value)
        val part = value - whole
        if (part < 0.02) return whole.toLong().toString()
        if (part > 0.98) return (whole + 1).toLong().toString()
        if (!decimal) {
            val near = FRACTIONS.minBy { abs(it.first - part) }
            if (abs(near.first - part) < 0.02) {
                return if (whole >= 1) "${whole.toLong()} ${near.second}" else near.second
            }
        }
        val rounded = (value * 100).roundToLong() / 100.0
        return rounded.toString().trimEnd('0').trimEnd('.')
    }
}
