package com.luum.michi.app.core

import com.luum.michi.app.InMemoryActiveProviderStore
import com.luum.michi.app.InMemoryCachedViewerStore
import com.luum.michi.app.TestAuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.InMemoryTokenStorage
import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.SessionManager
import com.luum.michi.app.core.session.domain.SessionState
import com.luum.michi.app.core.session.domain.Viewer
import com.luum.michi.app.testSessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun viewer(
    provider: ProviderId = ProviderId.AniList,
    name: String = "user",
) = Viewer(id = 1, name = name, provider = provider)

private fun freshToken() = AuthToken(
    accessToken = "abc",
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600L,
)

private fun expiredToken() = AuthToken(
    accessToken = "abc",
    savedAtEpochSeconds = 1L,
    expiresInSeconds = 1L,
)

/** Within the 60 s refresh margin but not expired. */
private fun expiringToken(refreshToken: String? = "refresh-1") = AuthToken(
    accessToken = "old",
    savedAtEpochSeconds = currentEpochSeconds() - 3550L,
    expiresInSeconds = 3600L,
    refreshToken = refreshToken,
)

private fun refreshedToken(refreshToken: String? = null) = AuthToken(
    accessToken = "new",
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600L,
    refreshToken = refreshToken,
)

private fun manager(
    storage: TokenStorage,
    viewerResult: NetworkResult<Viewer> = NetworkResult.Success(viewer()),
    cached: Viewer? = null,
    provider: TestAuthProvider = TestAuthProvider(viewerResult = viewerResult),
    onClearUserData: suspend () -> Unit = {},
): Triple<SessionManager, TestAuthProvider, InMemoryCachedViewerStore> {
    val cache = InMemoryCachedViewerStore(cached)
    val manager = testSessionManager(
        storage = storage,
        provider = provider,
        cachedViewerStore = cache,
        onClearUserData = onClearUserData,
    )
    return Triple(manager, provider, cache)
}

class SessionManagerTest {

    @Test
    fun bootstrapWithoutTokenGoesAnonymousWithoutFetching() {
        val storage = InMemoryTokenStorage()
        val (manager, provider, _) = manager(storage, NetworkResult.Success(viewer()))
        runBlocking { manager.bootstrap() }
        assertTrue(manager.state.value is SessionState.Anonymous)
        assertEquals(0, provider.viewerCalls)
    }

    @Test
    fun bootstrapWithExpiredTokenGoesAnonymous() {
        val storage = InMemoryTokenStorage()
        runBlocking { storage.save(expiredToken()) }
        val (manager, provider, _) = manager(storage, NetworkResult.Success(viewer()))
        runBlocking { manager.bootstrap() }
        assertTrue(manager.state.value is SessionState.Anonymous)
        assertEquals(0, provider.viewerCalls)
        assertNull(runBlocking { storage.load() }, "the dead token is dropped")
    }

    @Test
    fun bootstrapWithValidTokenAuthenticates() {
        val storage = InMemoryTokenStorage()
        runBlocking { storage.save(freshToken()) }
        val (manager, _, _) = manager(storage, NetworkResult.Success(viewer()))
        runBlocking { manager.bootstrap() }
        val state = manager.state.value
        assertTrue(state is SessionState.Authenticated)
        assertEquals("user", state.viewer.name)
    }

    @Test
    fun bootstrapFailureSurfacesErrorWithoutCache() {
        val storage = InMemoryTokenStorage()
        runBlocking { storage.save(freshToken()) }
        val error = NetworkError.Http(500, null)
        val (manager, _, _) = manager(storage, NetworkResult.Failure(error))
        runBlocking { manager.bootstrap() }
        val state = manager.state.value
        assertTrue(state is SessionState.Error)
        assertEquals(error, state.error)
    }

    @Test
    fun cachedViewerEntersTheShellAndRefreshes() {
        val storage = InMemoryTokenStorage()
        runBlocking { storage.save(freshToken()) }
        val (manager, _, cache) = manager(
            storage,
            NetworkResult.Success(Viewer(id = 1, name = "fresh")),
            cached = Viewer(id = 1, name = "cached"),
        )

        runBlocking { manager.bootstrap() }

        val state = manager.state.value
        assertTrue(state is SessionState.Authenticated)
        assertEquals("fresh", state.viewer.name)
        assertEquals("fresh", cache.stored?.name, "the fresh viewer is cached")
    }

    @Test
    fun cachedViewerSurvivesANetworkError() {
        val storage = InMemoryTokenStorage()
        runBlocking { storage.save(freshToken()) }
        val (manager, _, cache) = manager(
            storage,
            NetworkResult.Failure(NetworkError.Http(500, null)),
            cached = Viewer(id = 1, name = "cached"),
        )

        runBlocking { manager.bootstrap() }

        val state = manager.state.value
        assertTrue(state is SessionState.Authenticated, "keeps the cached shell, not Error")
        assertEquals("cached", state.viewer.name)
        assertEquals("cached", cache.stored?.name)
    }

    @Test
    fun unauthorizedClearsTokenAndCache() {
        val storage = InMemoryTokenStorage()
        runBlocking { storage.save(freshToken()) }
        val (manager, _, cache) = manager(
            storage,
            NetworkResult.Failure(NetworkError.Unauthorized(null)),
            cached = Viewer(id = 1, name = "cached"),
        )

        runBlocking { manager.bootstrap() }

        assertTrue(manager.state.value is SessionState.Anonymous)
        runBlocking { assertNull(storage.load()) }
        assertNull(cache.stored)
    }

    @Test
    fun oAuthCallbackSavesAndAuthenticates() {
        val storage = InMemoryTokenStorage()
        val token = freshToken()
        val provider = TestAuthProvider(
            callbackResult = { uri ->
                if (uri == "michi://oauth/callback#access_token=abc") {
                    NetworkResult.Success(token)
                } else {
                    null
                }
            },
        )
        val (manager, _, cache) = manager(storage, provider = provider)

        runBlocking { manager.onOAuthCallback("michi://oauth/callback#access_token=abc") }

        runBlocking { assertEquals(token, storage.load()) }
        assertTrue(manager.state.value is SessionState.Authenticated)
        assertEquals("user", cache.stored?.name)
    }

    @Test
    fun aFailedCallbackLeavesAGuestAnonymous() = runBlocking {
        val storage = InMemoryTokenStorage()
        val provider = TestAuthProvider(
            callbackResult = { NetworkResult.Failure(NetworkError.NoConnection) },
        )
        val (manager, _, _) = manager(storage, provider = provider)
        manager.bootstrap()
        assertTrue(manager.state.value is SessionState.Anonymous)

        manager.onOAuthCallback("michi://oauth/callback?error=access_denied")

        assertTrue(manager.state.value is SessionState.Anonymous)
    }

    @Test
    fun aLoginFailureIsExposedUntilClearedOrSucceeded() = runBlocking {
        val storage = InMemoryTokenStorage()
        val provider = TestAuthProvider(
            callbackResult = { NetworkResult.Failure(NetworkError.NoConnection) },
        )
        val (manager, _, _) = manager(storage, provider = provider)

        manager.onOAuthCallback("michi://oauth/callback?code=abc")
        assertEquals(provider.id, manager.lastLoginError.value)

        manager.clearLoginError()
        assertNull(manager.lastLoginError.value)

        manager.onOAuthCallback("michi://oauth/callback?code=abc")
        assertEquals(provider.id, manager.lastLoginError.value)
    }

    @Test
    fun aSuccessfulLoginClearsThePreviousError() = runBlocking {
        val storage = InMemoryTokenStorage()
        var fail = true
        val provider = TestAuthProvider(
            callbackResult = {
                if (fail) {
                    NetworkResult.Failure(NetworkError.NoConnection)
                } else {
                    NetworkResult.Success(freshToken())
                }
            },
        )
        val (manager, _, _) = manager(storage, provider = provider)

        manager.onOAuthCallback("michi://oauth/callback?code=abc")
        assertEquals(provider.id, manager.lastLoginError.value)

        fail = false
        manager.onOAuthCallback("michi://oauth/callback?code=abc")
        assertNull(manager.lastLoginError.value)
        assertTrue(manager.state.value is SessionState.Authenticated)
    }

    @Test
    fun aCancelledLoginDoesNotSetAnError() = runBlocking {
        val storage = InMemoryTokenStorage()
        val provider = TestAuthProvider(callbackResult = { null })
        val (manager, _, _) = manager(storage, provider = provider)

        manager.onOAuthCallback("michi://oauth/callback?error=access_denied")

        assertNull(manager.lastLoginError.value)
    }

    @Test
    fun logoutClearsTokenAndCache() {
        val storage = InMemoryTokenStorage()
        runBlocking { storage.save(freshToken()) }
        val (manager, _, cache) = manager(storage, NetworkResult.Success(viewer()))
        runBlocking {
            manager.bootstrap()
            manager.logout()
            assertNull(storage.load())
        }
        assertNull(cache.stored)
        assertTrue(manager.state.value is SessionState.Anonymous)
    }

    @Test
    fun onUnauthorizedMidSessionGoesAnonymous() {
        val storage = InMemoryTokenStorage()
        val token = freshToken()
        runBlocking { storage.save(token) }
        val (manager, _, cache) = manager(storage, NetworkResult.Success(viewer()), cached = viewer())
        runBlocking { manager.bootstrap() }
        assertTrue(manager.state.value is SessionState.Authenticated)

        runBlocking { manager.onUnauthorized(token) }

        assertTrue(manager.state.value is SessionState.Anonymous)
        runBlocking { assertNull(storage.load()) }
        assertNull(cache.stored)
    }

    @Test
    fun aStaleUnauthorizedDoesNotCloseANewerSession() {
        val storage = InMemoryTokenStorage()
        val old = freshToken()
        runBlocking { storage.save(old) }
        val (manager, _, _) = manager(storage, NetworkResult.Success(viewer()), cached = viewer())
        runBlocking { manager.bootstrap() }

        // A new token replaced the old one (e.g. re-login) before the 401 lands.
        val fresh = AuthToken(
            accessToken = "new-token",
            savedAtEpochSeconds = currentEpochSeconds(),
            expiresInSeconds = 3600L,
        )
        runBlocking { storage.save(fresh) }

        runBlocking { manager.onUnauthorized(old) }

        assertEquals(fresh, runBlocking { storage.load() }, "the stale 401 is ignored")
    }

    @Test
    fun tokenLookupIsScopedToTheRequestedProvider() = runBlocking {
        val aniListStorage = InMemoryTokenStorage()
        aniListStorage.save(freshToken())
        val malStorage = InMemoryTokenStorage()
        malStorage.save(freshToken().copy(accessToken = "mal-token"))
        val aniList = TestAuthProvider()
        val mal = TestAuthProvider(
            id = ProviderId.Mal,
            viewerResult = NetworkResult.Success(viewer(provider = ProviderId.Mal)),
        )
        val manager = testSessionManager(
            providers = listOf(aniList, mal),
            tokenStorages = mapOf(
                ProviderId.AniList to aniListStorage,
                ProviderId.Mal to malStorage,
            ),
            activeProviderStore = InMemoryActiveProviderStore(ProviderId.Mal),
        )

        assertNull(
            manager.validToken(ProviderId.AniList),
            "a MAL session never leaks its token to AniList",
        )
        assertEquals("mal-token", manager.validToken(ProviderId.Mal)?.accessToken)
        assertEquals(0, aniList.refreshCalls, "the other provider is never refreshed")
    }

    @Test
    fun aBootstrapLandingAfterLogoutCannotResurrectTheSession() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(freshToken())
        val gate = CompletableDeferred<Unit>()
        val provider = TestAuthProvider(viewerResult = NetworkResult.Success(viewer())).apply {
            viewerGate = gate
        }
        val manager = testSessionManager(storage = storage, provider = provider)

        val bootstrap = launch { manager.bootstrap() }
        yield() // bootstrap enters its viewer refresh and waits at the gate
        val logout = launch { manager.logout() }
        yield() // logout queues behind the serialized bootstrap
        gate.complete(Unit)
        bootstrap.join()
        logout.join()

        assertTrue(manager.state.value is SessionState.Anonymous)
    }

    @Test
    fun concurrentRefreshesShareOneRequest() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(expiringToken())
        val gate = CompletableDeferred<Unit>()
        val provider = TestAuthProvider(
            refreshResult = NetworkResult.Success(refreshedToken()),
        ).apply { refreshGate = gate }
        val manager = testSessionManager(storage = storage, provider = provider)

        val callers = List(6) { async { manager.validToken(ProviderId.AniList) } }
        yield()
        assertEquals(1, provider.refreshCalls, "all callers share the same refresh")
        gate.complete(Unit)
        val tokens = callers.awaitAll()

        assertTrue(tokens.all { it?.accessToken == "new" })
        assertEquals(1, provider.refreshCalls)
        assertEquals("new", storage.load()?.accessToken)
    }

    @Test
    fun refreshKeepsThePreviousRefreshToken() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(expiringToken("refresh-1"))
        val provider = TestAuthProvider(
            refreshResult = NetworkResult.Success(refreshedToken(refreshToken = null)),
        )
        val manager = testSessionManager(storage = storage, provider = provider)

        val token = manager.validToken(ProviderId.AniList)

        assertEquals("new", token?.accessToken)
        assertEquals("refresh-1", storage.load()?.refreshToken)
    }

    @Test
    fun refreshUnauthorizedClosesSessionAndClearsData() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(expiringToken())
        var clears = 0
        val cache = InMemoryCachedViewerStore(viewer())
        val provider = TestAuthProvider(
            refreshResult = NetworkResult.Failure(NetworkError.Unauthorized(null)),
        )
        val manager = testSessionManager(
            storage = storage,
            provider = provider,
            cachedViewerStore = cache,
            onClearUserData = { clears++ },
        )
        manager.bootstrap()
        assertTrue(manager.state.value is SessionState.Authenticated)

        val token = manager.validToken(ProviderId.AniList)

        assertNull(token)
        assertTrue(manager.state.value is SessionState.Anonymous)
        assertNull(storage.load())
        assertNull(cache.stored)
        assertEquals(1, clears)
    }

    @Test
    fun networkRefreshFailureKeepsSessionWhileTokenValid() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(expiringToken())
        var clears = 0
        val provider = TestAuthProvider(
            refreshResult = NetworkResult.Failure(NetworkError.NoConnection),
        )
        val manager = testSessionManager(
            storage = storage,
            provider = provider,
            onClearUserData = { clears++ },
        )
        manager.bootstrap()
        assertTrue(manager.state.value is SessionState.Authenticated)

        val token = manager.validToken(ProviderId.AniList)

        assertEquals("old", token?.accessToken, "the still-valid token is returned")
        assertTrue(manager.state.value is SessionState.Authenticated)
        assertEquals(0, clears, "a network failure never closes the session")
        assertEquals("old", storage.load()?.accessToken)
    }

    @Test
    fun bootstrapWithExpiredTokenAndRefreshAuthenticates() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(expiredToken().copy(refreshToken = "refresh-1"))
        val provider = TestAuthProvider(
            refreshResult = NetworkResult.Success(refreshedToken()),
        )
        val manager = testSessionManager(storage = storage, provider = provider)

        manager.bootstrap()

        assertTrue(manager.state.value is SessionState.Authenticated)
        assertEquals("new", storage.load()?.accessToken)
        assertEquals(1, provider.refreshCalls)
        assertEquals(1, provider.viewerCalls)
    }

    @Test
    fun callbackFromASecondProviderClosesTheFirstSession() = runBlocking {
        val aniListStorage = InMemoryTokenStorage()
        aniListStorage.save(freshToken())
        val otherStorage = InMemoryTokenStorage()
        val other = ProviderId("other")
        val aniListProvider = TestAuthProvider()
        val otherProvider = TestAuthProvider(
            id = other,
            label = "Other",
            viewerResult = NetworkResult.Success(viewer(provider = other, name = "other")),
            callbackResult = { uri ->
                if (uri == "michi://other/callback?code=1") {
                    NetworkResult.Success(
                        AuthToken(
                            accessToken = "other-token",
                            savedAtEpochSeconds = currentEpochSeconds(),
                            expiresInSeconds = 3600L,
                        ),
                    )
                } else {
                    null
                }
            },
        )
        val active = InMemoryActiveProviderStore(ProviderId.AniList)
        var clears = 0
        val cache = InMemoryCachedViewerStore(viewer())
        val manager = testSessionManager(
            providers = listOf(aniListProvider, otherProvider),
            tokenStorages = mapOf(
                ProviderId.AniList to aniListStorage,
                other to otherStorage,
            ),
            activeProviderStore = active,
            cachedViewerStore = cache,
            onClearUserData = { clears++ },
        )

        manager.onOAuthCallback("michi://other/callback?code=1")

        assertNull(aniListStorage.load(), "the previous provider's session is closed")
        assertEquals("other-token", otherStorage.load()?.accessToken)
        assertEquals(other, active.load())
        assertEquals(1, clears, "switching providers wipes user data once")
        val state = manager.state.value
        assertTrue(state is SessionState.Authenticated)
        assertEquals(other, state.provider)
        assertEquals(other, manager.activeProvider(), "the list router follows the active provider")
    }
}
