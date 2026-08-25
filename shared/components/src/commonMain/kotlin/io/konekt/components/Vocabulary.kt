package io.konekt.components

// The words a server may send in the open-string fields below, and the reason they are constants
// here rather than enums anywhere.
//
// An enum on the wire closes the set at the client's build date: a value the client does not know
// fails to decode and takes the screen with it. An open string degrades — an unrecognised word draws
// the ordinary variant — which is the same bargain kompot makes for a ColorToken and, since 0.31.0,
// for a checkbox_input variant. The constants exist because the SERVER is the side that has to spell
// the word, and a constant is how a shared string stops being spelled twice.
//
// A client that meets an unknown word here must draw the neutral form, never nothing. That rule is
// the one thing these objects cannot enforce, so it is tested instead.

// How much of a quota is left, as a judgement rather than a number: the client cannot decide it
// because "low" depends on the subscriber's own rate of use, which lives on the server.
object CounterStates {
    const val NORMAL = "normal"
    const val LOW = "low"
    const val EXHAUSTED = "exhausted"
}

// Availability of a plan in the catalogue. `LOADING` is a real state on the wire rather than a
// client-side flag, because a catalogue row that is still being priced is something the server knows
// and the client cannot guess.
object PlanStates {
    const val AVAILABLE = "available"
    const val SOLD_OUT = "sold_out"
    const val LOADING = "loading"
}

// The lifecycle of an eSIM profile. Ordered as it is lived; the two terminal states are separate
// because they mean opposite things to a subscriber — one can be resumed, the other cannot.
object EsimStatuses {
    const val ORDERED = "ordered"
    const val READY = "ready"
    const val INSTALLED = "installed"
    const val ACTIVE = "active"
    const val SUSPENDED = "suspended"
    const val TERMINATED = "terminated"
}

// What an order row is saying about money. `COMPENSATED` is not a failure and is not a success: it
// is the state the canvas draws as "450 ₽ returned to balance on 28 Jun — profile never activated",
// and it exists as its own word because rendering it as either of the other two is the misreading
// the screen is built to prevent.
object OrderStatuses {
    const val PENDING = "pending"
    const val AWAITING_CONFIRMATION = "awaiting_confirmation"
    const val COMPLETED = "completed"
    const val COMPENSATED = "compensated"
    const val FAILED = "failed"
}

// The three weights a banner or a snackbar carries. Deliberately the same vocabulary for both, since
// a message that starts inline and later becomes transient should not change its word.
object MessageTones {
    const val INFO = "info"
    const val LOW = "low"
    const val ERROR = "error"
}

// The shape a skeleton stands in for while a real one loads. It is drawn rather than left blank
// because an empty list and a loading list look identical, and only one of them is worth waiting for.
object SkeletonShapes {
    const val LINE = "line"
    const val ROW = "row"
    const val CARD = "card"
}
