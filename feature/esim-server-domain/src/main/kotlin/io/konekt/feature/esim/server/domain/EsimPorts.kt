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

interface EsimRepository {
    // Profiles that occupy a slot. A terminated one does not, which is the difference between a
    // subscriber who has used eight and one who has ever had eight.
    suspend fun countHeldBy(subscriberId: String): Int

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

    suspend fun save(record: EsimWizardRecord)
}

// Where an identifier comes from, so a test can have one it chose.
//
// The same argument as KonektClock: a value read from the environment inside the code under test is
// a value the test cannot assert on, and the assertion here is "one profile, not two".
fun interface EsimIds {
    fun next(): String
}
