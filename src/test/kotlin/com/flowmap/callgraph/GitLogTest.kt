package com.flowmap.callgraph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitLogTest {

    @Test fun `toWebBase normalizes common remote URL forms`() {
        val expected = "https://github.com/owner/repo"
        assertEquals(expected, GitLog.toWebBase("git@github.com:owner/repo.git"))
        assertEquals(expected, GitLog.toWebBase("git@github.com:owner/repo"))
        assertEquals(expected, GitLog.toWebBase("https://github.com/owner/repo.git"))
        assertEquals(expected, GitLog.toWebBase("https://github.com/owner/repo"))
        assertEquals(expected, GitLog.toWebBase("https://github.com/owner/repo/"))
        assertEquals(expected, GitLog.toWebBase("ssh://git@github.com/owner/repo.git"))
        assertEquals(expected, GitLog.toWebBase("http://github.com/owner/repo.git"))
    }

    @Test fun `toWebBase strips embedded credentials`() {
        assertEquals("https://github.com/owner/repo",
            GitLog.toWebBase("https://user:ghp_token@github.com/owner/repo.git"))
    }

    @Test fun `toWebBase keeps enterprise and other hosts`() {
        assertEquals("https://git.corp.example.com/team/svc",
            GitLog.toWebBase("git@git.corp.example.com:team/svc.git"))
    }

    @Test fun `toWebBase returns null for unusable input`() {
        assertNull(GitLog.toWebBase(""))
        assertNull(GitLog.toWebBase("   "))
        assertNull(GitLog.toWebBase("/local/path/repo"))
    }
}
