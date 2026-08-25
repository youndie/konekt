package io.konekt.openapi

// The vocabulary of `x-kompot-endpoint-kind`. Every constant below was read in the conformance kit
// at the version this build resolves — `kompot-tck` 0.31.0.74, `TckEndpoint.kt` and `TckRunner.kt` —
// rather than recalled from the readme.
//
// WHY IT MATTERS WHICH WORD IS USED. The kit selects its checks by this extension: `page` is what
// makes the pagination walk claim an endpoint, `updates_stream` is what keeps a `text/event-stream`
// out of the blind GET walk, `submit` is what a `perform` action's target must be. An operation
// carrying no extension reads back as `"unknown"`, and that is not an error — no check claims it and
// the report says so out loud, under "Not walked".
//
// So the honest answer for a route that serves none of this vocabulary is to declare nothing, and
// NOT to borrow the nearest-looking word. A kind asserts the SHAPE of the body; a wrong one turns a
// conformant server into a page of findings about a contract it never made.
object EndpointKind {
    const val EXTENSION = "x-kompot-endpoint-kind"

    // Answers a `KompotComponent` tree. `ScreenRouteKind` in `kompot-navigation` says exactly that
    // in its own comment: "screen" yields a KompotComponent.
    const val SCREEN = "screen"

    // Answers a `KompotPageResponse` — the next page of items plus the action that fetches the one
    // after it. `TckRunner.paginationTerminates` claims every endpoint of this kind.
    const val PAGE = "page"

    // Answers a `KompotAction`, which the client runs through the same handler chain as any other
    // intent. Written down in kompot-commands' own annotation on `KompotActionPerform.url`.
    const val SUBMIT = "submit"

    // A `text/event-stream` of `UpdateComponentMessage` frames. The kit keeps this kind out of the
    // blind walk deliberately: the body is a sequence of frames rather than one document.
    const val UPDATES_STREAM = "updates_stream"

    // Read in the kit and NOT used by this server, listed so the next person need not go back to the
    // jar to learn whether the vocabulary is closed: `form`, `graph`, `live_screen`, `wizard_resume`.
    // A route of ours grows one of these the day it starts answering the matching shape.
}
