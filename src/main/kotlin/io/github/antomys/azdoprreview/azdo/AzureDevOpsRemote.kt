package io.github.antomys.azdoprreview.azdo

import java.io.File
import java.net.URLDecoder

/** Organization, project and repository read from a Git remote URL. */
data class AzureDevOpsRemote(val organization: String, val project: String, val repository: String) {
    companion object {
        private val HTTPS = Regex("""^https?://(?:[^@/]+@)?dev\.azure\.com/([^/]+)/(?:([^/]+)/)?_git/([^/?#]+)""", RegexOption.IGNORE_CASE)
        private val VISUAL_STUDIO =
            Regex("""^https?://(?:[^@/]+@)?([^./]+)\.visualstudio\.com/(?:DefaultCollection/)?(?:([^/]+)/)?_git/([^/?#]+)""", RegexOption.IGNORE_CASE)
        private val REMOTE_SECTION = Regex("""^\[remote\s+"([^"]+)"]""")
        private val SSH =Regex("""^(?:ssh://)?[^@/]+@(?:ssh\.dev\.azure\.com|vs-ssh\.visualstudio\.com):(?:22/)?v3/([^/]+)/([^/]+)/([^/?#]+)""", RegexOption.IGNORE_CASE)

        /**
         * Parses `https://dev.azure.com/{org}/{project}/_git/{repo}` (optionally with `{user}@`),
         * `https://{org}.visualstudio.com/{project}/_git/{repo}` and `git@ssh.dev.azure.com:v3/{org}/{project}/{repo}`.
         * A URL without a project (`…/{org}/_git/{repo}`) means the project has the repository's name.
         */
        fun parse(url: String): AzureDevOpsRemote? {
            val trimmed = url.trim()
            val match = HTTPS.find(trimmed) ?: VISUAL_STUDIO.find(trimmed) ?: SSH.find(trimmed) ?: return null
            val (org, project, repo) = match.destructured
            val repository = decode(repo)
            return AzureDevOpsRemote(decode(org), if (project.isEmpty()) repository else decode(project), repository)
        }

        /** The first Azure DevOps remote in a `.git/config` text, preferring `origin`. */
        fun fromGitConfig(config: String): AzureDevOpsRemote? {
            val urls = mutableListOf<Pair<String, String>>()
            var remote: String? = null
            for (raw in config.lines()) {
                val line = raw.trim()
                if (line.startsWith("[")) {
                    remote = REMOTE_SECTION.find(line)?.groupValues?.get(1)
                    continue
                }
                val name = remote ?: continue
                if (line.substringBefore("=").trim().equals("url", ignoreCase = true)) urls += name to line.substringAfter("=").trim()
            }
            return urls.sortedBy { it.first != "origin" }.firstNotNullOfOrNull { parse(it.second) }
        }

        /**
         * Reads the Git config of the repository at [dir]. Handles a linked worktree, where `.git` is a file pointing to
         * `<main>/.git/worktrees/<name>` and the config sits in the main repository.
         */
        fun fromRepository(dir: File): AzureDevOpsRemote? {
            val dotGit = File(dir, ".git")
            val gitDir = when {
                dotGit.isDirectory -> dotGit
                dotGit.isFile -> dotGit.readText().lines().firstOrNull { it.startsWith("gitdir:") }
                    ?.substringAfter("gitdir:")?.trim()?.let { File(it).takeIf(File::isAbsolute) ?: File(dir, it) }
                    ?: return null
                else -> return null
            }
            val common = File(gitDir, "commondir").takeIf { it.isFile }?.readText()?.trim()
                ?.let { File(it).takeIf(File::isAbsolute) ?: File(gitDir, it) } ?: gitDir
            val config = File(common, "config").takeIf { it.isFile } ?: return null
            return fromGitConfig(config.readText())
        }

        /**
         * The Azure DevOps remote of the Git repository containing [dir]: the nearest folder up from [dir] that has a
         * `.git`, since a solution often sits below the repository root. Null without a repository or such a remote.
         */
        fun find(dir: File): AzureDevOpsRemote? =
            generateSequence(dir.absoluteFile) { it.parentFile }
                .firstOrNull { File(it, ".git").exists() }
                ?.let(::fromRepository)

        private fun decode(segment: String) = URLDecoder.decode(segment.replace("+", "%2B"), Charsets.UTF_8)
    }
}
