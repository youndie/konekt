package io.konekt.components

// THE ACTIONS konekt adds to the kompot wire, as names, for the same reason the components have a
// list — and for a sharper one.
//
// Components are GENERATED: a `@KompotComponentMarker` type reaches the registry through KSP, and
// forgetting one is a build failure. Actions are not. An action is registered by hand, in a
// `SerializersModule` on each side, and leaving it out of either compiles perfectly and fails at the
// one press that matters — with a decoding error naming a discriminator, in a log nobody is reading,
// on a screen somebody is looking at.
//
// That has happened three times here: `submit_form` registered on neither side and answering 500 on
// the login screen, and twice before it. So the names live in a module both sides depend on, and a
// test on each side asks its own `Json` whether every one of them resolves. A name added here without
// a registration fails that test; a registration without a name here is invisible to it, which is the
// half worth keeping expensive.
//
// STRINGS AND NOT TYPES, deliberately: this module cannot see the feature modules the actions live
// in, and giving it that dependency to hold a list would invert the graph for a checklist.
val konektActionWireNames: List<String> =
    listOf(
        "buy_plan",
        "esim_wizard_step",
        "sign_out",
    )
