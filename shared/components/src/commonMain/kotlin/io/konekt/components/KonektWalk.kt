package io.konekt.components

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.PaginatedListComponent
import io.github.youndie.kompot.standard.RowComponent

// EVERY NODE OF A SCREEN, in one place, because there were five of these and each went stale alone.
//
// `KompotComponent` declares no children. Nesting is a convention of each type — `column` and `row`
// have `children`, `paginated_list` has `initialItems` AND an `emptyState`, `bottom_nav` has `items`
// of a holder that is not a component at all — so anything walking a tree has to keep a list of
// which types to descend into.
//
// FIVE COPIES OF THAT LIST EXISTED — one in `:e2e`, two in `:server`'s tests and two in
// `:client`'s — and
// adding a container did not break them. It made them look at LESS: each reported an ABSENCE — "no
// balance label", "no formatted amount in the recording", "the balance did not come back" — about a
// tree that had the thing one level below where the walk stopped, and the accusation landed on the
// product. It happened with `paginated_list` when the history screen was built and again with
// `surface`, three at once.
//
// IT LIVES IN `commonMain` RATHER THAN IN A TEST SOURCE SET, for the reason `konektWireNames` gives
// beside it: which of these types nest is a fact about the wire vocabulary, and a fact about the
// vocabulary belongs where the vocabulary is — reachable from every module that has to read it,
// without a fixtures artefact per platform.
//
// This is still a hand-kept list, so it has a guard: `WalkCoversEveryContainerTest` compares what
// this reaches against what a walk of the raw JSON reaches, and the JSON cannot miss a nesting.
fun KompotComponent.konektWalk(): List<KompotComponent> =
    listOf(this) +
        when (this) {
            is ColumnComponent -> children.flatMap { it.konektWalk() }

            is RowComponent -> children.flatMap { it.konektWalk() }

            // BOTH FIELDS. `initialItems` is the list and `emptyState` is the node drawn when there
            // is none — a component in its own right, and one that three of the four copies of this
            // walk never reached. An empty list is exactly when the empty state is the only thing on
            // the screen, so a walk that skips it is blind precisely when there is one thing to see.
            is PaginatedListComponent -> initialItems.flatMap { it.konektWalk() } + emptyState?.konektWalk().orEmpty()

            is SurfaceComponent -> children.flatMap { it.konektWalk() }

            // A LEAF, and `bottom_nav` is one of them on purpose: its `items` carry actions, not
            // components, so there is nothing below it to draw. That is a fact about the type rather
            // than an omission — and it is why a walk that needs the ACTIONS of a tree has to read
            // the JSON instead, as `EveryScreenIsReachableTest` does.
            else -> emptyList()
        }
