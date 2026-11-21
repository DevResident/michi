package com.luum.michi.app.core.mal.domain

import io.ktor.http.parseQueryString

/**
 * A MAL redirect back to the app. MAL answers with
 * `michi://oauth/callback?code=…&state=…`, so the code lives in the query (not
 * in a fragment like AniList's implicit grant). [code] is null when the user
 * denied/cancelled and MAL only sent an `error`/`state`.
 */
internal data class MalOAuthCallback(val code: String?, val state: String?)

/**
 * Parses a MAL callback, or null when the URI does not belong to MAL. AniList
 * callbacks keep their `#access_token=…` fragment and are therefore rejected
 * here (no query, no `code`).
 */
internal fun parseMalOAuthCallback(uri: String): MalOAuthCallback? {
    val withoutFragment = uri.substringBefore('#')
    val base = withoutFragment.substringBefore('?')
    if (!base.equals(MalOAuthConfig.RedirectUri, ignoreCase = true)) return null

    val query = withoutFragment.substringAfter('?', "")
    if (query.isEmpty()) return null

    val params = parseQueryString(query)
    val state = params["state"]?.takeIf { it.isNotBlank() }
    val code = params["code"]?.takeIf { it.isNotBlank() }
    return MalOAuthCallback(code = code, state = state)
}
