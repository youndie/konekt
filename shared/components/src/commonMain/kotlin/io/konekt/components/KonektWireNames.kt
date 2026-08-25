package io.konekt.components

// The nine names konekt adds to the kompot wire, in one list because more than one thing has to walk
// them: the registration tests, the schema profile check, and — when it exists — the server's own
// completeness gate over the screens it can build.
//
// A name here without a component fails the registration test; a component without a name here is
// invisible to every check in this repository, which is the failure worth making expensive. Keeping
// the list beside the components rather than in a test source set is what lets the JVM-only spec
// module read the same copy.
val konektWireNames: List<String> =
    listOf(
        "usage_counter_card",
        "plan_card",
        "esim_card",
        "esim_qr",
        "order_row",
        "banner",
        "snackbar",
        "step_meter",
        "skeleton",
    )
