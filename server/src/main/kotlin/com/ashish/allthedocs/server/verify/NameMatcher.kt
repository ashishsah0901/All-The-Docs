package com.ashish.allthedocs.server.verify

import java.text.Normalizer

object NameMatcher {
    private val honorifics = setOf("mr", "mrs", "ms", "miss", "dr", "shri", "sri", "smt", "kumari", "km", "master", "mx")

    fun matches(expected: String?, actual: String?): Boolean? {
        val a = tokens(expected ?: return null)
        val b = tokens(actual ?: return null)
        if (a.isEmpty() || b.isEmpty()) return null
        val (shorter, longer) = if (a.size <= b.size) a to b else b to a
        if (shorter.size == 1 && longer.size > 1) return if (shorter.single() in longer) null else false
        val remaining = longer.toMutableList()
        for (token in shorter) {
            val match = remaining.firstOrNull { tokenMatches(token, it) } ?: return false
            remaining.remove(match)
        }
        return shorter.any { it.length > 1 }
    }

    internal fun tokens(name: String): List<String> {
        val decomposed = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return decomposed.lowercase()
            .replace(Regex("[^\\p{L}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it !in honorifics }
    }

    private fun tokenMatches(a: String, b: String): Boolean = when {
        a == b -> true
        a.length == 1 -> b.startsWith(a)
        b.length == 1 -> a.startsWith(b)
        else -> false
    }
}
