package io.github.antomys.azdoprreview.azdo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AzureDevOpsRemoteTest {
    private val expected = AzureDevOpsRemote("fabrikam", "Fiber Project", "web-app")

    @Test
    fun `parses https remotes with and without the user name`() {
        assertEquals(expected, AzureDevOpsRemote.parse("https://dev.azure.com/fabrikam/Fiber%20Project/_git/web-app"))
        assertEquals(expected, AzureDevOpsRemote.parse("https://fabrikam@dev.azure.com/fabrikam/Fiber%20Project/_git/web-app"))
    }

    @Test
    fun `parses ssh remotes`() {
        assertEquals(expected, AzureDevOpsRemote.parse("git@ssh.dev.azure.com:v3/fabrikam/Fiber%20Project/web-app"))
    }

    @Test
    fun `parses old visualstudio_com remotes`() {
        assertEquals(expected, AzureDevOpsRemote.parse("https://fabrikam.visualstudio.com/Fiber%20Project/_git/web-app"))
        assertEquals(expected, AzureDevOpsRemote.parse("https://fabrikam.visualstudio.com/DefaultCollection/Fiber%20Project/_git/web-app"))
    }

    @Test
    fun `takes the repository name as the project when the URL has none`() {
        assertEquals(AzureDevOpsRemote("fabrikam", "web-app", "web-app"), AzureDevOpsRemote.parse("https://dev.azure.com/fabrikam/_git/web-app"))
    }

    @Test
    fun `ignores other hosts`() {
        assertNull(AzureDevOpsRemote.parse("https://github.com/fabrikam/web-app.git"))
        assertNull(AzureDevOpsRemote.parse("git@github.com:fabrikam/web-app.git"))
    }

    @Test
    fun `prefers origin in a git config`() {
        val config = """
            [core]
            	bare = false
            [remote "upstream"]
            	url = https://dev.azure.com/other/p/_git/r
            	fetch = +refs/heads/*:refs/remotes/upstream/*
            [remote "origin"]
            	url = git@ssh.dev.azure.com:v3/fabrikam/Fiber%20Project/web-app
            [branch "main"]
            	remote = origin
        """.trimIndent()
        assertEquals(expected, AzureDevOpsRemote.fromGitConfig(config))
    }

    @Test
    fun `reads the config of a linked worktree from the main repository`(@TempDir dir: File) {
        val main = File(dir, "main/.git").apply { mkdirs() }
        File(main, "config").writeText("[remote \"origin\"]\n\turl = https://dev.azure.com/fabrikam/Fiber%20Project/_git/web-app\n")
        val worktreeGit = File(main, "worktrees/wt").apply { mkdirs() }
        File(worktreeGit, "commondir").writeText("../..\n")
        val worktree = File(dir, "wt").apply { mkdirs() }
        File(worktree, ".git").writeText("gitdir: ${worktreeGit.absolutePath}\n")

        assertEquals(expected, AzureDevOpsRemote.fromRepository(worktree))
        assertEquals(expected, AzureDevOpsRemote.fromRepository(File(dir, "main")))
        assertNull(AzureDevOpsRemote.fromRepository(File(dir, "missing")))
    }

    @Test
    fun `finds the repository above a solution folder`(@TempDir dir: File) {
        File(dir, ".git").mkdirs()
        File(dir, ".git/config").writeText("[remote \"origin\"]\n\turl = https://dev.azure.com/fabrikam/Fiber%20Project/_git/web-app\n")
        val solution = File(dir, "src/WebApp").apply { mkdirs() }

        assertEquals(expected, AzureDevOpsRemote.find(solution))
        assertEquals(expected, AzureDevOpsRemote.find(dir))
    }

    @Test
    fun `stops at the nearest repository even without an Azure DevOps remote`(@TempDir dir: File) {
        File(dir, ".git").mkdirs()
        File(dir, ".git/config").writeText("[remote \"origin\"]\n\turl = https://dev.azure.com/fabrikam/Fiber%20Project/_git/web-app\n")
        val nested = File(dir, "vendor/lib").apply { mkdirs() }
        File(nested, ".git").mkdirs()
        File(nested, ".git/config").writeText("[remote \"origin\"]\n\turl = https://github.com/someone/lib.git\n")

        assertNull(AzureDevOpsRemote.find(nested))
    }
}
