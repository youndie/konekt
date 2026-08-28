package io.konekt.feature.esim.server.domain

// The subscription manager that issues eSIM profiles — GSMA's SM-DP+, which is outside this system's
// boundary and is mocked.
//
// It is modelled with a REFUSAL and not merely a failure, and that is the whole reason it is an
// interface rather than a function returning a code. The canvas names the eight-profile limit as
// "the failure this flow actually hits in the field", so a mock that can only succeed or throw
// cannot draw the one frame the flow was designed around.
interface SmDpPlus {
    // Asked before anything is issued, with the number of profiles the subscriber already holds.
    //
    // The COUNT comes from us and the RULE comes from here, which is the honest split: what has been
    // issued is our record, and how many a device may hold is the manager's business. A mock that
    // counted for itself would be a mock that owns our data.
    suspend fun capacityFor(profilesHeld: Int): Capacity

    suspend fun issue(subscriberId: String): IssuedProfile

    sealed interface Capacity {
        data object Available : Capacity

        data class Refused(
            val code: String,
            val text: String,
        ) : Capacity
    }
}

// What the manager hands back: the profile's number and the string that installs it.
data class IssuedProfile(
    val iccid: String,
    val activationCode: String,
)

// The words a refusal may carry. Constants because the SERVER is the side that spells them and a
// client branches on them; the same bargain as the component vocabulary.
object EsimRefusals {
    // The device cannot store another profile. Not a validation error and not a server fault — the
    // request was fine and the answer is no.
    const val SLOT_LIMIT = "slot_limit"
}

// WHAT A LINE HOLDS, split by the only question two of the three callers are really asking: is it on
// a device yet.
//
// It used to be one number, `countHeldBy`, and one number cannot answer both. That count means SLOTS
// — it exists for the device's eight-profile limit — and the profile screen printed it under the word
// "installed" while the home screen used it to decide whether to offer the install flow at all. So a
// subscriber who had bought a profile and never scanned it was told they had one installed, and the
// door to the wizard disappeared at exactly the moment they needed it (`B-69`).
//
// The BUCKETS are the domain's and the statuses are the data layer's: this module does not know the
// wire vocabulary, and which status string means "on a device" is a fact about the table.
data class EsimHoldings(
    // Occupies a slot. A terminated profile does not, which is the difference between a subscriber
    // who has used eight and one who has ever had eight.
    val held: Int,
    // Issued and not on a device — so there is something to install, and something the subscriber has
    // paid for and cannot yet use.
    val awaitingInstall: Int,
    // On a device, whatever it is doing there.
    val installed: Int,
) {
    companion object {
        val none = EsimHoldings(held = 0, awaitingInstall = 0, installed = 0)
    }
}

interface EsimRepository {
    // ONE QUESTION AND ONE ANSWER. Two shapes of "how many profiles" is how two screens come to
    // disagree about it, which is precisely what happened.
    suspend fun holdingsOf(subscriberId: String): EsimHoldings

    suspend fun create(
        subscriberId: String,
        iccid: String,
        activationCode: String,
    ): EsimProfile

    suspend fun findById(esimId: String): EsimProfile?

    // The last step of the flow is the subscriber saying the profile is on the device, and this is
    // where that lands. Without it every issued profile stays `ready` forever and the lifecycle in
    // `EsimStatuses` is a vocabulary nothing ever speaks.
    suspend fun markInstalled(esimId: String)
}

interface EsimWizardSessions {
    suspend fun create(record: EsimWizardRecord)

    suspend fun find(wizardId: String): EsimWizardRecord?

    // THE RUN THIS SUBSCRIBER IS IN THE MIDDLE OF, if there is one.
    //
    // It exists so that opening the install flow twice is opening it twice rather than starting two
    // runs. Without it the only way in is `create`, and a screen that could be navigated to would
    // write a row every time somebody arrived at it — including the arrival that is a refresh.
    //
    // `finished` is already a column and `subscriber_id` is already indexed, so this costs no
    // migration; what it buys is that "install my eSIM" is a place rather than an event.
    suspend fun findUnfinishedBy(subscriberId: String): EsimWizardRecord?

    suspend fun save(record: EsimWizardRecord)
}

// Where an identifier comes from, so a test can have one it chose.
//
// The same argument as KonektClock: a value read from the environment inside the code under test is
// a value the test cannot assert on, and the assertion here is "one profile, not two".
fun interface EsimIds {
    fun next(): String
}
