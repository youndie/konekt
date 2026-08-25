package io.konekt.client.session

import io.github.youndie.kompot.auth.UpdateSessionAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The two tokens, and which run of sessions they belong to.
data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
)

// Where the tokens survive, or do not.
//
// An interface with an in-memory default, because the honest platform answer is different on every
// platform — the keychain on iOS, `EncryptedSharedPreferences` on Android — and this build has
// neither of those targets yet. Naming the seam now costs nothing and keeps the wrong answer, a
// plain file, from becoming the default by accident.
interface SessionStore {
    suspend fun read(): SessionTokens?

    suspend fun write(tokens: SessionTokens)

    suspend fun clear()
}

// Survives nothing. Correct for a desktop run and for tests, and stated rather than implied so that
// the first mobile target has an obvious thing to replace.
class InMemorySessionStore(
    initial: SessionTokens? = null,
) : SessionStore {
    private var tokens: SessionTokens? = initial

    override suspend fun read(): SessionTokens? = tokens

    override suspend fun write(tokens: SessionTokens) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}

// The client's half of "and the client stores it".
//
// It is a STORE and not an interceptor, and that division is what makes the rest simple: ktor's
// bearer plugin already knows how to attach a token and how to ask for a new one after a 401, so
// what it lacks is somewhere to keep them. Writing our own interceptor would mean re-implementing
// the part of the protocol the plugin gets right — including that the FIRST request goes out with no
// token and comes back 401, which is the plugin working rather than a fault.
class KonektSession(
    private val store: SessionStore = InMemorySessionStore(),
) {
    // One writer at a time. Two requests failing with 401 together both reach the refresh, and
    // without this both would spend the same refresh token — which, with rotation and reuse
    // detection on the server, ends the family and signs the subscriber out.
    private val mutex = Mutex()

    private val state = MutableStateFlow<SessionTokens?>(null)

    val tokens: StateFlow<SessionTokens?> get() = state

    val isSignedIn: kotlinx.coroutines.flow.Flow<Boolean> get() = state.map { it != null }

    suspend fun load(): SessionTokens? =
        mutex.withLock {
            val stored = store.read()
            state.value = stored
            stored
        }

    // What the client does with `update_session`, and the reason kompot ships that action at all: the
    // server hands over a session inside an ordinary action response, so signing in needs no protocol
    // of its own. The action is the toolkit's; acting on it is the application's.
    suspend fun apply(action: UpdateSessionAction) {
        adopt(SessionTokens(action.accessToken, action.refreshToken))
    }

    suspend fun adopt(tokens: SessionTokens) {
        mutex.withLock {
            store.write(tokens)
            state.value = tokens
        }
    }

    suspend fun clear() {
        mutex.withLock {
            store.clear()
            state.value = null
        }
    }

    // Serialised through the same mutex as everything else, so a burst of 401s produces one refresh
    // rather than one per request. The block returns null when the refresh itself failed, and then
    // the session is ended here rather than left holding tokens the server has already refused.
    suspend fun refresh(exchange: suspend (String) -> SessionTokens?): SessionTokens? =
        mutex.withLock {
            val current = state.value ?: store.read() ?: return@withLock null
            val refreshed = exchange(current.refreshToken)

            if (refreshed == null) {
                store.clear()
                state.value = null
            } else {
                store.write(refreshed)
                state.value = refreshed
            }

            refreshed
        }
}
