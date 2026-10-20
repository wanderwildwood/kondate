package com.wanderwildwood.kondate.data

/**
 * A recipe as words to send elsewhere: to Notes as a shopping list, or as plain text to anyone.
 * The headings are passed in, so they come from the app's strings rather than from here.
 */
object Plain {

    /** The ingredients still to get, as a Markdown checklist, which is what Notes ticks off. */
    fun shoppingList(lines: List<String>): String = lines.joinToString("\n", postfix = "\n") { "- [ ] $it" }

    /** The same list for an app that does not read Markdown. */
    fun shoppingText(title: String, lines: List<String>): String =
        (listOf(title, "") + lines.map { "• $it" }).joinToString("\n")

    /**
     * The whole recipe as plain text: name, yield, ingredients at the chosen [factor], the
     * method numbered, the notes, and where it came from.
     */
    fun recipe(r: Recipe, factor: Double, yieldLine: String, ingredientsHeading: String, methodHeading: String, notesHeading: String): String {
        val out = StringBuilder()
        out.append(r.name).append('\n')
        if (yieldLine.isNotEmpty()) out.append(yieldLine).append('\n')
        if (r.ingredients.isNotEmpty()) {
            out.append('\n').append(ingredientsHeading).append('\n')
            r.ingredients.forEach { out.append("• ").append(Quantities.scale(it, factor)).append('\n') }
        }
        if (r.steps.isNotEmpty()) {
            out.append('\n').append(methodHeading).append('\n')
            var section = ""
            r.steps.forEachIndexed { i, s ->
                if (s.section != section && s.section.isNotEmpty()) out.append(s.section).append('\n')
                section = s.section
                out.append(i + 1).append(". ").append(s.text).append('\n')
            }
        }
        if (r.notes.isNotBlank()) out.append('\n').append(notesHeading).append('\n').append(r.notes.trim()).append('\n')
        if (r.url.isNotBlank()) out.append('\n').append(r.url).append('\n')
        return out.toString().trimEnd() + "\n"
    }

    /**
     * Steps from the editor's box, one to a line. A line starting with # names the part of the
     * recipe the steps under it belong to ("# For the sauce"), a # alone ends the part, and a
     * blank line is nothing.
     */
    fun stepsFrom(text: String): List<Step> {
        val out = mutableListOf<Step>()
        var section = ""
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                section = line.trimStart('#').trim()
                continue
            }
            out += Step(line, section)
        }
        return out
    }

    /** Steps back into the editor's box, the way [stepsFrom] reads them. */
    fun stepsText(steps: List<Step>): String {
        val out = StringBuilder()
        var section = ""
        for (s in steps) {
            if (s.section != section) {
                // A bare # closes a part, for the rare recipe with loose steps after its parts.
                if (s.section.isNotEmpty()) out.append("# ").append(s.section).append('\n') else out.append("#\n")
                section = s.section
            }
            out.append(s.text).append('\n')
        }
        return out.toString().trimEnd()
    }
}
