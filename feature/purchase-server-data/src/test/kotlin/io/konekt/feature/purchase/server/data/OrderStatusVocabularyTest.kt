package io.konekt.feature.purchase.server.data

import io.konekt.components.OrderStatuses
import io.konekt.feature.purchase.server.domain.OrderStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// TWO LISTS OF THE SAME THING, HELD TOGETHER — and they had drifted in BOTH directions before this
// existed. `OrderStatuses` declared `failed`, which no producer emits (petich's FAILED maps to
// COMPENSATED, deliberately and documented), and omitted `rejected` and `compensating`, which the
// server does emit on `PurchaseOrderResponse.status` and `TopUpResponse.status`.
//
// The consequence was not cosmetic. `HistoryScreen` mapped everything it did not name to `pending`
// and "Awaiting confirmation", so an order a RULE had refused told the subscriber to wait for
// something that was never coming.
//
// WALKED RATHER THAN LISTED. A third hand-written list here would be a third thing to forget; the
// enum is the producer, so the enum is what this reads.
class OrderStatusVocabularyTest {
    // The constants of the dictionary object, read off the class. `OrderStatuses` lives in a
    // multiplatform module and cannot use reflection itself; this test is JVM and can.
    private fun declaredWords(): Set<String> =
        OrderStatuses::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { field ->
                field.isAccessible = true
                field.get(OrderStatuses) as String
            }.toSet()

    @Test
    fun `every status the server can emit is a word the dictionary declares`() {
        val produced = OrderStatus.entries.map { it.wireName }.toSet()

        // The guard on the guard: reflection that found nothing would make both directions vacuous,
        // and it fails silently — an empty set is a subset of everything.
        assertTrue(declaredWords().size >= 5, "reflection found ${declaredWords().size} words — is the shape right?")

        assertEquals(
            emptySet(),
            produced - declaredWords(),
            "the server emits words the component dictionary does not declare, so a client meets them as unknown",
        )
    }

    @Test
    fun `every word the dictionary declares is one the server can emit`() {
        // The other direction, and the one that found `failed`. A constant nothing produces is a
        // client branch nothing can reach — the same shape as a function with no caller, one list
        // away from the code that would have shown it.
        assertEquals(
            emptySet(),
            declaredWords() - OrderStatus.entries.map { it.wireName }.toSet(),
            "the dictionary declares words no OrderStatus produces",
        )
    }
}
