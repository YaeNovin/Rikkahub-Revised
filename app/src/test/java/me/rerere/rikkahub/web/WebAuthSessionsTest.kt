package me.rerere.rikkahub.web

import org.junit.Assert.*
import org.junit.Test

class WebAuthSessionsTest {
    private val secret = "test-only-secret-not-a-real-credential"

    @Test fun `logout revokes exactly one session and restart invalidates all sessions`() {
        val sessions = WebAuthSessions()
        val first = sessions.issue(secret).first
        val second = sessions.issue(secret).first
        assertTrue(sessions.accepts(first, secret))
        sessions.revoke(first, secret)
        sessions.revoke(first, secret)
        assertFalse(sessions.accepts(first, secret))
        assertTrue(sessions.accepts(second, secret))
        assertFalse(WebAuthSessions().accepts(second, secret))
        assertTrue(sessions.accepts(sessions.issue(secret).first, secret))
    }

    @Test fun `expired tampered and rotated-secret tokens fail closed`() {
        var time = System.currentTimeMillis()
        val sessions = WebAuthSessions(now = { time })
        val (token, expiry) = sessions.issue(secret)
        assertFalse(sessions.accepts(token, "rotated-secret"))
        assertFalse(sessions.accepts(token + "tampered", secret))
        assertFalse(sessions.accepts(null, secret))
        sessions.revoke(token + "tampered", secret)
        assertTrue(sessions.accepts(token, secret))
        time = expiry
        assertFalse(sessions.accepts(token, secret))
    }

    @Test fun `bounded registry expires oldest sessions instead of reviving revoked ones`() {
        val sessions = WebAuthSessions(capacity = 2)
        val first = sessions.issue(secret).first
        val second = sessions.issue(secret).first
        val third = sessions.issue(secret).first
        assertFalse(sessions.accepts(first, secret))
        assertTrue(sessions.accepts(second, secret))
        assertTrue(sessions.accepts(third, secret))
    }

    @Test fun `logout cookie clears the exact path with browser protections intact`() {
        assertTrue(webSessionCookie().startsWith("rikkahub_access=; Max-Age=0; Path=/api;"))
        assertTrue(webSessionCookie().contains("HttpOnly; SameSite=Strict"))
        assertTrue(webSessionCookie("test", secure = true).endsWith("; Secure"))
    }
}
