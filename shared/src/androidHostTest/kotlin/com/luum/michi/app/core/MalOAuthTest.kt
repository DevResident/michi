package com.luum.michi.app.core

import com.luum.michi.app.core.mal.domain.MalOAuthConfig
import com.luum.michi.app.core.mal.domain.MalStateLength
import com.luum.michi.app.core.mal.domain.MalVerifierLength
import com.luum.michi.app.core.mal.domain.PendingMalAuth
import com.luum.michi.app.core.mal.domain.Pkce
import com.luum.michi.app.core.mal.domain.base64UrlNoPadding
import com.luum.michi.app.core.mal.domain.generatePkce
import com.luum.michi.app.core.mal.domain.parseMalOAuthCallback
import com.luum.michi.app.core.mal.repository.SettingsStorePendingMalAuthStore
import com.luum.michi.app.settings.FakeSettingsStore
import io.ktor.http.parseQueryString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MalOAuthTest {

    @Test
    fun pkceUsesTheAllowedAlphabetAndLengths() {
        val pkce = generatePkce()
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toSet()

        assertEquals(MalVerifierLength, pkce.verifier.length)
        assertEquals(MalStateLength, pkce.state.length)
        assertTrue(pkce.verifier.all { it in allowed }, "verifier: ${pkce.verifier}")
        assertTrue(pkce.state.all { it in allowed }, "state: ${pkce.state}")
    }

    @Test
    fun base64UrlUsesTheUrlSafeAlphabetWithoutPadding() {
        assertEquals(
            "-_8",
            base64UrlNoPadding(byteArrayOf(0xFB.toByte(), 0xFF.toByte())),
        )
        assertEquals("AAAA", base64UrlNoPadding(ByteArray(3)))
    }

    @Test
    fun twoLoginsNeverShareTheSamePkce() {
        val first = generatePkce()
        val second = generatePkce()

        assertNotEquals(first.verifier, second.verifier)
        assertNotEquals(first.state, second.state)
    }

    @Test
    fun authorizeUrlCarriesThePkceAndRedirectParameters() {
        val pkce = Pkce(verifier = "v".repeat(64), state = "s".repeat(32))

        val url = MalOAuthConfig.buildAuthorizeUrl(pkce, clientId = "client id")
        val query = parseQueryString(url.substringAfter('?'))

        assertEquals("code", query["response_type"])
        assertEquals("client id", query["client_id"])
        assertEquals(pkce.verifier, query["code_challenge"])
        assertEquals("plain", query["code_challenge_method"])
        assertEquals(pkce.state, query["state"])
        assertEquals(MalOAuthConfig.RedirectUri, query["redirect_uri"])
        assertTrue(
            "redirect_uri=michi%3A%2F%2Foauth%2Fcallback" in url,
            "the redirect URI must be percent-encoded: $url",
        )
    }

    @Test
    fun thePendingAuthRoundTripsThroughTheSettingsStore() {
        val store = SettingsStorePendingMalAuthStore(FakeSettingsStore())
        assertNull(store.load())

        val pending = PendingMalAuth(
            verifier = "the-verifier",
            state = "the-state",
            createdAtEpochSeconds = 123L,
        )
        store.save(pending)
        assertEquals(pending, store.load())

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun aMalCallbackCarriesTheCodeAndState() {
        val callback = parseMalOAuthCallback(
            "michi://oauth/callback?code=the-code&state=the-state",
        )

        assertEquals("the-code", callback?.code)
        assertEquals("the-state", callback?.state)
    }

    @Test
    fun aDeniedCallbackCarriesNoCodeButKeepsTheState() {
        val callback = parseMalOAuthCallback(
            "michi://oauth/callback?error=access_denied&state=the-state",
        )

        assertNotNull(callback)
        assertNull(callback.code)
        assertEquals("the-state", callback.state)
    }

    @Test
    fun anAniListFragmentCallbackIsNotMine() {
        assertNull(parseMalOAuthCallback("michi://oauth/callback#access_token=abc"))
    }

    @Test
    fun anotherRedirectIsNotMine() {
        assertNull(parseMalOAuthCallback("https://other.app/callback?code=abc&state=xyz"))
        assertNull(parseMalOAuthCallback("michi://oauth/callback-other?code=abc&state=xyz"))
    }
}
