package io.konekt.screens

import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.SizeType

// A BUTTON THAT FILLS ITS ROW, said by the SERVER.
//
// The canvas draws every primary button full-bleed and this build drew them the width of their
// label — "Send me a code" as wide as those four words. The fix could have gone in the renderer, and
// that is the version worth naming and rejecting: a client that made every button full-width would
// be a client with an opinion about layout, and the next button that should hug its text would have
// to fight it.
//
// kompot's modifier vocabulary is five nodes — Background, Gradient, Padding, Size, Weight — and
// `Size(width = Fill)` is exactly this sentence. So the side that owns the design says it, and a
// button that should hug simply does not carry it.
val FILLS_THE_ROW: List<KompotModifierNode> = listOf(KompotModifierNode.Size(width = SizeType.Fill))

// WHAT TAKES THE SPACE LEFT OVER, beside something that hugs its content.
//
// The canvas gives `Top up` `flex:1` and lets `History` be as wide as the word — two controls of
// obviously different weight, which is what makes one of them the thing to press. `Size(width = Fill)`
// on both would make them equal again and `Weight` is the node that expresses "the rest of the row".
//
// It is also how a row pushes its second item to the far end: the first carries the weight, so the
// second sits against the edge. That is the balance block's head — the amount on the left, the
// number on the right.
val TAKES_THE_SPACE: List<KompotModifierNode> = listOf(KompotModifierNode.Weight(1f))
