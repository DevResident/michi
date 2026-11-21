package com.luum.michi.app.core

import com.luum.michi.app.InMemoryPendingMalAuthStore
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.OAuthBrowser
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.mal.domain.PendingMalAuth
import com.luum.michi.app.core.mal.repository.MalAuthProvider
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.Viewer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingOAuthBrowser : OAuthBrowser {
    var lastUrl: String? = null

    override fun open(url: String) {
        lastUrl = url
    }
}

private fun malHttp(engine: MockEngine): MalHttp = MalHttp(
    httpClient = HttpClient(engine),
    clientId = "test-client",
    redirectUri = "michi://oauth/callback",
)

private fun pending(
    state: String = "the-state",
    createdAt: Long = currentEpochSeconds(),
) = PendingMalAuth(
    verifier = "the-verifier",
    state = state,
    createdAtEpochSeconds = createdAt,
)

private fun provider(
    engine: MockEngine,
    store: InMemoryPendingMalAuthStore = InMemoryPendingMalAuthStore(),
    browser: OAuthBrowser = RecordingOAuthBrowser(),
    now: () -> Long = { currentEpochSeconds() },
): MalAuthProvider = MalAuthProvider(
    browser = browser,
    http = malHttp(engine),
    pendingStore = store,
    now = now,
)

private fun callbackUrl(code: String? = null, state: String = "the-state"): String {
    val codeParam = code?.let { "code=$it&" }.orEmpty()
    return "michi://oauth/callback?${codeParam}state=$state"
}

class MalAuthProviderTest {

    @Test
    fun openSavesThePendingAndOpensTheAuthorizeUrl() {
        val store = InMemoryPendingMalAuthStore()
        val browser = RecordingOAuthBrowser()
        val provider = provider(
            engine = MockEngine { error("open must not touch the network") },
            store = store,
            browser = browser,
        )

        provider.open()

        val pending = assertNotNull(store.pending)
        val query = parseQueryString(assertNotNull(browser.lastUrl).substringAfter('?'))
        assertEquals(pending.verifier, query["code_challenge"])
        assertEquals("plain", query["code_challenge_method"])
        assertEquals(pending.state, query["state"])
        assertEquals("code", query["response_type"])
    }

    @Test
    fun anAniListCallbackDoesNotTouchThePending(): Unit = runBlocking {
        val store = InMemoryPendingMalAuthStore(pending())
        var calls = 0
        val provider = provider(MockEngine { calls++; error("must not call") })

        val result = provider.handleCallback("michi://oauth/callback#access_token=abc")

        assertNull(result)
        assertEquals(0, calls)
        assertNotNull(store.pending, "another provider's callback never drops it")
    }

    @Test
    fun aCodeWithoutPendingIsRejectedWithoutARequest() = runBlocking {
        val store = InMemoryPendingMalAuthStore()
        var calls = 0
        val provider = provider(MockEngine { calls++; error("must not call") })

        val result = provider.handleCallback(callbackUrl(code = "abc"))

        assertTrue(result is NetworkResult.Failure)
        assertEquals(0, calls)
        assertNull(store.pending)
    }

    @Test
    fun anExpiredPendingIsRejectedWithoutARequest() = runBlocking {
        val now = 1_000_000L
        val store = InMemoryPendingMalAuthStore(pending(createdAt = now - 601L))
        var calls = 0
        val provider = provider(MockEngine { calls++; error("must not call") }, store = store, now = { now })

        val result = provider.handleCallback(callbackUrl(code = "abc"))

        assertTrue(result is NetworkResult.Failure)
        assertEquals(0, calls)
        assertNull(store.pending)
    }

    @Test
    fun aStateMismatchIsRejectedWithoutARequest() = runBlocking {
        val store = InMemoryPendingMalAuthStore(pending(state = "expected"))
        var calls = 0
        val provider = provider(MockEngine { calls++; error("must not call") }, store = store)

        val result = provider.handleCallback(callbackUrl(code = "abc", state = "wrong"))

        assertTrue(result is NetworkResult.Failure)
        assertEquals(0, calls)
        assertNull(store.pending)
    }

    @Test
    fun aDeniedCallbackDropsAMatchingPendingWithoutChangingTheSession() = runBlocking {
        val store = InMemoryPendingMalAuthStore(pending())
        val provider = provider(MockEngine { error("a denial never exchanges") }, store = store)

        val result = provider.handleCallback(
            "michi://oauth/callback?error=access_denied&state=the-state",
        )

        assertNull(result)
        assertNull(store.pending)
    }

    @Test
    fun aDenialWithAnotherStateKeepsThePending(): Unit = runBlocking {
        val store = InMemoryPendingMalAuthStore(pending())
        val provider = provider(MockEngine { error("a denial never exchanges") }, store = store)

        val result = provider.handleCallback(
            "michi://oauth/callback?error=access_denied&state=another",
        )

        assertNull(result)
        assertNotNull(store.pending)
    }

    @Test
    fun aValidCodeExchangesItOnceAndClearsThePending() = runBlocking {
        val store = InMemoryPendingMalAuthStore(pending())
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                "https://myanimelist.net/v1/oauth2/token",
                request.url.toString(),
            )
            assertEquals(
                ContentType.Application.FormUrlEncoded,
                request.body.contentType?.withoutParameters(),
            )
            val form = request.body as FormDataContent
            assertEquals("test-client", form.formData["client_id"])
            assertEquals("authorization_code", form.formData["grant_type"])
            assertEquals("the-code", form.formData["code"])
            assertEquals("the-verifier", form.formData["code_verifier"])
            assertEquals("michi://oauth/callback", form.formData["redirect_uri"])
            respond(
                """{"token_type":"Bearer","expires_in":3600,"access_token":"access","refresh_token":"refresh"}""",
                HttpStatusCode.OK,
            )
        }
        val provider = provider(engine, store = store)

        val result = provider.handleCallback(callbackUrl(code = "the-code"))

        assertTrue(result is NetworkResult.Success)
        assertEquals("access", result.value.accessToken)
        assertEquals("refresh", result.value.refreshToken)
        assertEquals("Bearer", result.value.tokenType)
        assertEquals(3600L, result.value.expiresInSeconds)
        assertEquals(1, calls)
        assertNull(store.pending)
    }

    @Test
    fun aFailedExchangeStillClearsThePending() = runBlocking {
        val store = InMemoryPendingMalAuthStore(pending())
        val provider = provider(
            engine = MockEngine { throw UnknownHostException() },
            store = store,
        )

        val result = provider.handleCallback(callbackUrl(code = "the-code"))

        assertTrue(result is NetworkResult.Failure)
        assertEquals(NetworkError.NoConnection, result.error)
        assertNull(store.pending)
    }

    @Test
    fun refreshUsesTheRefreshGrant() = runBlocking {
        var form: io.ktor.http.Parameters? = null
        val engine = MockEngine { request ->
            form = (request.body as FormDataContent).formData
            respond(
                """{"token_type":"Bearer","expires_in":7200,"access_token":"new","refresh_token":"refresh-2"}""",
                HttpStatusCode.OK,
            )
        }
        val provider = provider(engine)
        val old = AuthToken(
            accessToken = "old",
            savedAtEpochSeconds = currentEpochSeconds(),
            refreshToken = "refresh-1",
        )

        val result = provider.refresh(old)

        assertTrue(result is NetworkResult.Success)
        assertEquals("new", result.value.accessToken)
        assertEquals("refresh-2", result.value.refreshToken)
        assertEquals(7200L, result.value.expiresInSeconds)
        assertEquals("refresh_token", form?.get("grant_type"))
        assertEquals("refresh-1", form?.get("refresh_token"))
        assertEquals("test-client", form?.get("client_id"))
    }

    @Test
    fun anInvalidRefreshGrantBecomesUnauthorized() = runBlocking {
        val body = """{"error":"invalid_grant","message":"invalid refresh token"}"""
        val engine = MockEngine { respond(body, HttpStatusCode.BadRequest) }
        val provider = provider(engine)

        val result = provider.refresh(
            AuthToken(
                accessToken = "old",
                savedAtEpochSeconds = currentEpochSeconds(),
                refreshToken = "refresh-1",
            ),
        )

        assertTrue(result is NetworkResult.Failure)
        assertEquals(NetworkError.Unauthorized(body), result.error)
    }

    @Test
    fun aNetworkRefreshFailureStaysANetworkError() = runBlocking {
        val provider = provider(MockEngine { throw UnknownHostException() })

        val result = provider.refresh(
            AuthToken(
                accessToken = "old",
                savedAtEpochSeconds = currentEpochSeconds(),
                refreshToken = "refresh-1",
            ),
        )

        assertTrue(result is NetworkResult.Failure)
        assertEquals(NetworkError.NoConnection, result.error)
    }

    @Test
    fun fetchViewerMapsTheMalUser() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("api.myanimelist.net", request.url.host)
            assertEquals("/v2/users/@me", request.url.encodedPath)
            assertEquals("id,name,picture", request.url.parameters["fields"])
            assertEquals("Bearer access", request.headers["Authorization"])
            respond(
                """{"id":42,"name":"fer","picture":"https://cdn.mal/x.jpg"}""",
                HttpStatusCode.OK,
            )
        }
        val provider = provider(engine)

        val result = provider.fetchViewer(
            AuthToken(accessToken = "access", savedAtEpochSeconds = currentEpochSeconds()),
        )

        assertTrue(result is NetworkResult.Success)
        assertEquals(
            Viewer(
                id = 42,
                name = "fer",
                avatarUrl = "https://cdn.mal/x.jpg",
                provider = ProviderId.Mal,
            ),
            result.value,
        )
    }

    @Test
    fun aRejectedViewerCallIsUnauthorized() = runBlocking {
        val body = """{"error":"invalid_token"}"""
        val engine = MockEngine { respond(body, HttpStatusCode.Unauthorized) }
        val provider = provider(engine)

        val result = provider.fetchViewer(
            AuthToken(accessToken = "access", savedAtEpochSeconds = currentEpochSeconds()),
        )

        assertTrue(result is NetworkResult.Failure)
        assertEquals(NetworkError.Unauthorized(body), result.error)
    }
}
