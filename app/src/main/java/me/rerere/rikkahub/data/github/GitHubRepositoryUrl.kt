package me.rerere.rikkahub.data.github

import java.net.URI
import java.util.Locale

/** Only public github.com repository homepages, not profiles, special routes or subresources. */
object GitHubRepositoryUrl {
    private val ownerPattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
    private val repoPattern = Regex("[A-Za-z0-9_.-]{1,100}")
    private val reservedOwners = setOf("orgs", "users", "settings", "search", "topics", "collections",
        "marketplace", "features", "sponsors", "login", "join", "signup", "logout", "notifications",
        "explore", "pulls", "issues", "discussions", "codespaces", "account", "apps", "enterprise",
        "organizations", "new", "security", "site", "about", "pricing", "customer-stories", "readme")

    fun parse(url: String): String? = runCatching {
        val uri = URI(url.trim())
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
            uri.host?.lowercase(Locale.ROOT) !in setOf("github.com", "www.github.com") ||
            uri.rawUserInfo != null || uri.port != -1) return null
        // Queries/fragments can select a different view; preserve them as ordinary links.
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath ?: return null
        if (!path.startsWith('/') || '%' in path || '\\' in path) return null
        canonicalFullName(path.removePrefix("/").removeSuffix("/"))
    }.getOrNull()

    fun canonicalFullName(value: String): String? {
        val parts = value.split('/')
        if (parts.size != 2) return null
        val (owner, repo) = parts
        if (!ownerPattern.matches(owner) || "--" in owner || !repoPattern.matches(repo) ||
            repo.all { it == '.' } || repo.endsWith(".git", true) ||
            owner.lowercase(Locale.ROOT) in reservedOwners) return null
        return "$owner/$repo".lowercase(Locale.ROOT)
    }

    fun url(fullName: String): String = "https://github.com/${requireNotNull(canonicalFullName(fullName))}"
}
