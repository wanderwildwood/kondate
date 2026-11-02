package com.wanderwildwood.kondate.data

import java.text.Normalizer

/**
 * A recipe's labels: the words a person files it under ("Salads", "Italian"). They are kept in
 * the recipe's `keywords`, the comma-joined field schema.org and Nextcloud Cookbook both use for
 * exactly this, so they go to the Nextcloud and come back with nothing added to the file. The
 * keywords a page gave when a recipe was read from a link are labels too.
 *
 * Two spellings that differ only in case or accents are one label; the first spelling met is kept.
 */
object Labels {

    /** The labels in [text], split at commas and line ends: trimmed, spaces collapsed, no repeats, in the order given. */
    fun parse(text: String): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (part in text.split(',', '\n', ';')) {
            val label = part.trim().replace(SPACES, " ")
            if (label.isNotEmpty() && seen.add(key(label))) out += label
        }
        return out
    }

    /** [labels] as the keywords field holds them. */
    fun join(labels: List<String>): String = parse(labels.joinToString(",")).joinToString(", ")

    /** Every label across [recipes], each once, in alphabetical order (accents and case aside). */
    fun all(recipes: List<Recipe>): List<String> {
        val first = LinkedHashMap<String, String>()
        recipes.forEach { r -> parse(r.keywords).forEach { first.putIfAbsent(key(it), it) } }
        return first.values.sortedBy { key(it) }
    }

    /** [labels] without repeats, alphabetical (accents and case aside). */
    fun sorted(labels: List<String>): List<String> = parse(labels.joinToString(",")).sortedBy { key(it) }

    /** Whether [text] holds [label]. */
    fun has(text: String, label: String): Boolean = parse(text).any { key(it) == key(label) }

    /** [text] with [label] added at the end, or taken out if it was there. */
    fun toggled(text: String, label: String): String {
        val now = parse(text)
        val k = key(label)
        return if (now.any { key(it) == k }) join(now.filter { key(it) != k }) else join(now + label)
    }

    /** The recipes carrying [label]; all of them when [label] is null or blank. */
    fun filter(recipes: List<Recipe>, label: String?): List<Recipe> {
        if (label.isNullOrBlank()) return recipes
        val k = key(label)
        return recipes.filter { r -> parse(r.keywords).any { key(it) == k } }
    }

    private val SPACES = Regex("\\s+")
    private val MARKS = Regex("\\p{Mn}+")

    private fun key(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
}
