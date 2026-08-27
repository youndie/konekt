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

    // Answers a `KompotFormResponse` — a `FormSchema` plus the tree that renders it. The kit's
    // `form-fields` check claims every endpoint of this kind and, until `B-20`, found none: it was
    // one of the five checks with nothing to visit.
    const val FORM = "form"

    // Answers a `FormPatch` — updates, cleared fields and a focus, and no tree at all. It had NO KIND
    // here until `0.33.1.91`, and the absence was honest rather than an oversight: the kit read four
    // kinds and a patch was none of them, so the nearest-looking word would have entered the
    // `form-fields` check, found no schema in the body and passed. youndie/kompot#93 is what closed
    // the gap; `TckRunner.patchesNameDeclaredFields` is what reads this constant.
    const val PATCH = "patch"

    // Answers a `NavigationGraph`: every destination this deployment serves, by deeplink, with the
    // address behind each and the SHAPE that address answers. `TckRunner.navigationGraphResolves`
    // follows every route to its screen and holds the route's declared kind against the kind the
    // description gives the same address — the route says what a client will parse, the description
    // says what the server will send, and nothing else compares the two.
    const val GRAPH = "graph"

    // Read in the kit and NOT used by this server, listed so the next person need not go back to the
    // jar to learn whether the vocabulary is closed: `live_screen`, `wizard_resume`.
    // A route of ours grows one of these the day it starts answering the matching shape.
}
