package dev.firetvlite.iptv.data.film

import dev.firetvlite.iptv.R

fun omdbIssueMessage(code: String?): Int? = when (code) {
    null -> null
    "AUTH", "HTTP_401", "HTTP_403" -> R.string.omdb_issue_auth
    "REMOTE_LIMIT" -> R.string.omdb_issue_daily_limit
    "LOCAL_BUDGET", "HTTP_429" -> R.string.omdb_issue_limit
    else -> R.string.omdb_issue_error
}
