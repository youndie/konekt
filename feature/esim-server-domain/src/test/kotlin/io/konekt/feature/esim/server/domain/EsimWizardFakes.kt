package io.konekt.feature.esim.server.domain

import kotlin.time.Instant

// Hand-written rather than MockK, and not out of principle: all three of these hold state across
// calls — a slot count that changes when a profile is created, a session that is read back after it
// was saved — and a stubbed call that returns a fixed value cannot express "and then it was
// different". A test built on stubs here would pass without the second write ever happening.

class FakeEsims(
    var held: Int = 0,
) : EsimRepository {
    val created = mutableListOf<EsimProfile>()
    val installed = mutableListOf<String>()

    // The wizard's capacity rule only reads `held`; the buckets are the screens' question and are
    // derived here from what this fake has actually created, so a test cannot set them to disagree
    // with the profiles it holds.
    override suspend fun holdingsOf(subscriberId: String): EsimHoldings =
        EsimHoldings(
            held = held,
            awaitingInstall = created.count { it.status == "ready" || it.status == "ordered" },
            installed = created.count { it.status == "installed" || it.status == "active" },
        )

    override suspend fun create(
        subscriberId: String,
        iccid: String,
        activationCode: String,
    ): EsimProfile {
        val profile =
            EsimProfile(
                id = "esim-${created.size + 1}",
                subscriberId = subscriberId,
                iccid = iccid,
                status = "ready",
                activationCode = activationCode,
                createdAt = Instant.fromEpochMilliseconds(0),
            )
        created += profile
        held += 1
        return profile
    }

    override suspend fun findById(esimId: String): EsimProfile? = created.firstOrNull { it.id == esimId }

    // Newest first and terminated ones skipped, the same rule the real query follows.
    override suspend fun heldBy(subscriberId: String): EsimProfile? =
        created.lastOrNull { it.subscriberId == subscriberId && it.status != "terminated" }

    override suspend fun markInstalled(esimId: String) {
        installed += esimId
    }
}

class FakeSessions : EsimWizardSessions {
    val rows = mutableMapOf<String, EsimWizardRecord>()
    var writes = 0

    override suspend fun create(record: EsimWizardRecord) {
        rows[record.id] = record
        writes += 1
    }

    override suspend fun find(wizardId: String): EsimWizardRecord? = rows[wizardId]

    override suspend fun findUnfinishedBy(subscriberId: String): EsimWizardRecord? =
        rows.values
            .filter { it.subscriberId == subscriberId && !it.session.isFinished }
            .maxByOrNull { it.id }

    override suspend fun save(record: EsimWizardRecord) {
        rows[record.id] = record
        writes += 1
    }
}

class FakeSmDpPlus(
    private val limit: Int = 8,
    private val refusalText: String = "no room",
) : SmDpPlus {
    var issued = 0

    override suspend fun capacityFor(profilesHeld: Int): SmDpPlus.Capacity =
        if (profilesHeld < limit) {
            SmDpPlus.Capacity.Available
        } else {
            SmDpPlus.Capacity.Refused(EsimRefusals.SLOT_LIMIT, refusalText)
        }

    override suspend fun issue(subscriberId: String): IssuedProfile {
        issued += 1
        return IssuedProfile(
            iccid = "894450000000123456$issued",
            activationCode = "LPA:1\$rsp.konekt.io\$8F214C9$issued",
        )
    }
}
