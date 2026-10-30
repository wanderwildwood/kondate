package com.wanderwildwood.kondate.importer

import com.wanderwildwood.kondate.data.Durations
import com.wanderwildwood.kondate.data.Recipe
import com.wanderwildwood.kondate.data.Step

/**
 * A recipe draft made from text copied out of a browser or an email: the one route that works
 * whatever a site thinks of apps. Nothing is fetched; it only reads the words it is given.
 *
 * The first line is the name. Lines under an Ingredients heading are the ingredients, lines
 * under Instructions, Directions, Method, Steps or Preparation are the method. A Serves or
 * Yield line and Prep, Cook or Total lines fill those boxes. Without headings, a line that
 * starts with a quantity is an ingredient and a numbered line is a step. Whatever it cannot
 * place goes to the notes, so no word is lost; the person edits the draft before saving.
 */
object PastedRecipe {

    private enum class Mode { NONE, INGREDIENTS, STEPS, NOTES }

    private val MARKUP = Regex("""(\*\*|__|`)""")
    private val BULLET = Regex("""^(?:\[[ xX]?]\s*|[•·‣◦▪▫■□☐☑✓✔►▶○●*\-–—+]\s*)+""")
    private val NUMBERING = Regex("""^(?:step\s*)?\d{1,2}\s*(?:[.:)]|\s[-–—])\s+""", RegexOption.IGNORE_CASE)
    private val NUMBER_ONLY = Regex("""^(?:step\s*)?\d{1,2}\s*[.:)]?$""", RegexOption.IGNORE_CASE)
    private val QUANTITY_FIRST = Regex(
        """^(?:\d|[½¼¾⅓⅔⅛]|(?:a|one|two|three|half)\s+(?:pinch|dash|handful|few|bunch|clove|can|tin|small|large|medium|cup|tsp|tbsp)\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val INGREDIENTS_HEADING = Regex("""^(?:ingredients?(?:\s+list)?(?:\s+for\b.*)?|what you(?:'|’)?ll need|you(?:'|’)?ll need)$""")
    private val STEPS_HEADING = Regex(
        """^(?:(?:cooking|preparation|recipe)\s+)?(?:instructions?|directions?|method|steps?|preparation|procedure)(?:\s+steps?)?$""",
    )
    private val NOTES_HEADING = Regex("""^(?:recipe\s+|cook(?:'|’)?s\s+)?(?:notes?|tips?)$""")
    private val END_HEADING = Regex(
        """^(?:nutrition(?:\s+(?:facts|information|info))?|equipment|comments?|reviews?|related(?:\s+recipes?)?|""" +
            """you may also like|more recipes?|share(?:\s+this.*)?|leave a (?:comment|reply)|rate this recipe|""" +
            """did you make this.*|storage|print recipe)$""",
    )

    private const val NUMBER_WORDS = "one|two|three|four|five|six|eight|ten|twelve"
    private val LABEL = Regex(
        """(?i)\b(serves|servings?|serving size|yield|makes|prep(?:aration)?(?:\s+time)?|cook(?:ing)?(?:\s+time)?|total(?:\s+time)?|ready\s+in)\b""" +
            """(?=\s*:|\s+(?:\d|[½¼¾⅓⅔]|(?:$NUMBER_WORDS)\b))""",
    )
    private val LABEL_STRICT = Regex(
        """(?i)\b(serves|servings?|serving size|yield|makes|prep(?:aration)?(?:\s+time)?|cook(?:ing)?(?:\s+time)?|total(?:\s+time)?|ready\s+in)\b(?=\s*:)""",
    )

    fun parse(text: String): Recipe {
        var name = ""
        var yield = ""
        var prep = ""
        var cook = ""
        var total = ""
        val ingredients = mutableListOf<String>()
        val steps = mutableListOf<Step>()
        val notes = mutableListOf<String>()
        var mode = Mode.NONE
        var seenSteps = false

        for (raw in text.replace("\r", "").lines()) {
            val line = raw.replace(MARKUP, "").replace(' ', ' ').trim()
            if (line.isEmpty()) continue
            if (name.isEmpty()) {
                name = line.trimStart('#').trim().trimEnd(':').trim()
                continue
            }
            val heading = headingOf(line)
            if (heading != null) {
                when {
                    INGREDIENTS_HEADING.matches(heading) -> { mode = Mode.INGREDIENTS; continue }
                    STEPS_HEADING.matches(heading) -> { mode = Mode.STEPS; seenSteps = true; continue }
                    NOTES_HEADING.matches(heading) -> { mode = Mode.NOTES; continue }
                    END_HEADING.matches(heading) -> { mode = Mode.NOTES; notes += line.trimEnd(':'); continue }
                }
            }
            val meta = metaIn(line, strict = mode == Mode.INGREDIENTS || mode == Mode.STEPS)
            if (meta != null) {
                for ((label, value) in meta) {
                    when (kindOf(label)) {
                        Kind.YIELD -> if (yield.isEmpty()) yield = yieldText(value)
                        Kind.PREP -> if (prep.isEmpty()) prep = timeText(value)
                        Kind.COOK -> if (cook.isEmpty()) cook = timeText(value)
                        Kind.TOTAL -> if (total.isEmpty()) total = timeText(value)
                    }
                }
                continue
            }
            val bare = line.replace(BULLET, "").trim()
            if (bare.isEmpty()) continue
            when (mode) {
                Mode.INGREDIENTS -> ingredients += bare
                Mode.STEPS -> {
                    if (NUMBER_ONLY.matches(bare)) continue
                    steps += Step(bare.replace(NUMBERING, "").trim())
                }
                Mode.NOTES -> notes += line
                Mode.NONE -> when {
                    NUMBERING.containsMatchIn(bare) && !seenSteps && bare.length > 12 -> steps += Step(bare.replace(NUMBERING, "").trim())
                    QUANTITY_FIRST.containsMatchIn(bare) && bare.length <= 100 && !NUMBERING.containsMatchIn(bare) -> ingredients += bare
                    else -> notes += line
                }
            }
        }
        return Recipe(
            id = "",
            name = name,
            recipeYield = yield,
            prepTime = prep,
            cookTime = cook,
            totalTime = total,
            ingredients = ingredients,
            steps = steps,
            notes = notes.joinToString("\n"),
        )
    }

    /** A line that is only a heading, lower-cased and bare: "Ingredients:", "## Method", "INSTRUCTIONS". */
    private fun headingOf(line: String): String? {
        val h = line.trimStart('#', ' ').trimEnd(':', ' ', '#').lowercase()
        return if (h.isNotEmpty() && h.length <= 40) h else null
    }

    private enum class Kind { YIELD, PREP, COOK, TOTAL }

    private fun kindOf(label: String): Kind {
        val l = label.lowercase()
        return when {
            l.startsWith("prep") -> Kind.PREP
            l.startsWith("cook") -> Kind.COOK
            l.startsWith("total") || l.startsWith("ready") -> Kind.TOTAL
            else -> Kind.YIELD
        }
    }

    /**
     * The label and value pairs of a line that is made of them ("Prep Time: 10 mins  Cook Time: 20 mins"),
     * or null when the line does not start with a label. Inside the ingredients and the method
     * a colon is required, so "Cook 5 minutes until golden" stays a step.
     */
    private fun metaIn(line: String, strict: Boolean): List<Pair<String, String>>? {
        if (line.length > 140) return null
        val body = line.replace(BULLET, "")
        val found = (if (strict) LABEL_STRICT else LABEL).findAll(body).toList()
        if (found.isEmpty() || found[0].range.first != 0) return null
        return found.mapIndexed { i, m ->
            val end = if (i + 1 < found.size) found[i + 1].range.first else body.length
            m.value to body.substring(m.range.last + 1, end).trimStart(' ', ':', '-', '–').trim(' ', '|', ',', ';', '·', '•')
        }.takeIf { pairs -> pairs.all { it.second.isNotEmpty() } }
    }

    private fun yieldText(value: String): String =
        if (value.all { it.isDigit() } ) "$value servings" else value

    /** "1 hour 15 minutes" as an ISO duration; words it cannot read ("overnight") as they were written. */
    private fun timeText(value: String): String {
        val seconds = Durations.inText(value).firstOrNull()?.seconds
        val minutes = seconds?.let { (it + 30) / 60 }
        return if (minutes != null && minutes > 0) Durations.isoOfMinutes(minutes) else value
    }
}
