package io.konekt.feature.shell.shared.api

import io.github.youndie.kompot.KompotAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

// LEAVING, and it is an action rather than a `navigate` for the reason `buy_plan` is one: it changes
// something. A session has to be given up on both sides — the refresh family revoked on the server,
// the tokens dropped on the client — and the screen that comes after depends on that having worked.
//
// It carries nothing. Which session is ending is the one the request is authenticated with, and an
// action naming a session would be an action a client could send about somebody else's.
@Serializable
@SerialName("sign_out")
class SignOutAction : KompotAction {
    // A class rather than an object, because kotlinx-serialization treats an object as a singleton
    // with no members and the polymorphic decode still needs a constructor to call. Equality is not
    // used anywhere and is not worth a data class with no data.
    override fun equals(other: Any?): Boolean = other is SignOutAction

    override fun hashCode(): Int = SIGN_OUT.hashCode()
}

// Registered by hand, like every other action in this build — actions are NOT generated, so leaving
// this out fails nothing at build time and everything at the one press that matters. That has cost
// this repository three separate incidents, which is why `KonektActionWireNames` and the guard over
// it now exist.
// PUT THIS TEXT ON THE CLIPBOARD (`B-115`): the activation code under the QR, for the subscriber on a
// desktop who has no camera to point at their own screen. The client does it and the server learns
// nothing, which is the whole of the contract — a copy that reported back would be a credential in
// an access log. A client that predates the action draws the button and presses nothing.
@Serializable
@SerialName("copy")
data class CopyAction(
    val text: String,
) : KompotAction

val shellActionsSerializersModule =
    SerializersModule {
        polymorphic(KompotAction::class) {
            subclass(SignOutAction::class)
            subclass(CopyAction::class)
        }
    }

const val SIGN_OUT: String = "sign_out"
