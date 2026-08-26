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
// THESE ARE EXACTLY THE WORDS `OrderStatus.wireName` PRODUCES, and `OrderStatusVocabularyTest` walks
// the enum to keep it so — in both directions. It used to declare `failed`, which no producer ever
// emits (petich's FAILED maps to COMPENSATED, deliberately), and to omit `rejected` and
// `compensating`, which the server does emit. Two lists of the same thing, disagreeing both ways.
object OrderStatuses {
    const val PENDING = "pending"
    const val AWAITING_CONFIRMATION = "awaiting_confirmation"
    const val COMPLETED = "completed"

    // A rule refused before anything happened. Its own word because "pending" tells a subscriber to
    // wait for something that will never come, and the history row said exactly that.
    const val REJECTED = "rejected"

    const val COMPENSATED = "compensated"

    // In flight, or stuck because a compensating step itself failed — the one state that needs a
    // person, which is why it is not folded into COMPENSATED.
    const val COMPENSATING = "compensating"
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

// How much a button matters. kompot deliberately fixes no set of emphases — `button.variant` is an
// open string named by the design system, exactly like a colour token — so the set is the
// application's to name, and this is where konekt names it.
//
// It is not a component and so it is not in `konektWireNames`: that list is the nine types the canvas
// defines, and this is a word one of kompot's own types carries.
object ButtonEmphasis {
    const val PRIMARY = "primary"

    // The one beside it that should not compete: Back, Cancel, Not now. A client that does not know
    // the word draws its ordinary button, which is wrong but harmless — the reverse default, drawing
    // everything as primary, gives a screen two equal answers to one question.
    const val QUIET = "quiet"
}
