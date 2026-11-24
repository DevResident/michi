package com.luum.michi.app.core.network.domain

/**
 * How many points of AniList's per-minute budget one request spends.
 *
 * AniList charges each top-level field of the operation, not each HTTP call:
 * a query with nine `Page` aliases costs 9 of the 30 points a minute, the same
 * as nine separate requests (measured on the live API through
 * `X-RateLimit-Remaining`). The limiter must spend the same points, or it lets
 * a cold start through that the server then answers with a 429 and a minute
 * of `Retry-After`.
 *
 * Counts the fields at depth one of the first selection set. Arguments,
 * nested selections, string literals and comments are skipped. Never below 1.
 */
internal fun graphQLRequestCost(query: String): Int {
    var braces = 0
    var parens = 0
    var count = 0
    var i = 0
    val n = query.length
    while (i < n) {
        val c = query[i]
        when {
            c == '#' -> while (i < n && query[i] != '\n') i++
            c == '"' -> {
                i++
                while (i < n && query[i] != '"') {
                    if (query[i] == '\\') i++
                    i++
                }
            }
            c == '(' -> parens++
            c == ')' -> parens--
            c == '{' -> braces++
            c == '}' -> {
                braces--
                // The operation's selection set closed: later definitions
                // (fragments) are not top-level fields.
                if (braces == 0 && count > 0) return count
            }
            braces == 1 && parens == 0 && (c.isLetter() || c == '_') -> {
                val start = i
                while (i < n && (query[i].isLetterOrDigit() || query[i] == '_')) i++
                var j = i
                while (j < n && query[j].isWhitespace()) j++
                // `alias: field` is one field: skip the alias, count the field.
                if (j < n && query[j] == ':') {
                    i = j + 1
                    continue
                }
                if (i > start) count++
                continue
            }
            braces == 1 && parens == 0 && c == '.' && query.startsWith("...", i) -> {
                // A fragment spread or inline fragment: one field at least.
                count++
                i += 3
                // Skip `Name` or `on Type`, so neither counts as a field.
                fun skipName(): String {
                    while (i < n && query[i].isWhitespace()) i++
                    val start = i
                    while (i < n && (query[i].isLetterOrDigit() || query[i] == '_')) i++
                    return query.substring(start, i)
                }
                if (skipName() == "on") skipName()
                continue
            }
        }
        i++
    }
    return count.coerceAtLeast(1)
}
