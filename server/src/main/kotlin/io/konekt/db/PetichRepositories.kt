package io.konekt.db

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import ru.workinprogress.petich.OutboxAwarePetichRepository
import ru.workinprogress.petich.postgres.OutboxEventsTable
import ru.workinprogress.petich.postgres.PetichTable

// A WORKAROUND WITH AN EXPIRY DATE — youndie/petich#8.
//
// `ExposedPetichRepository` is compiled into the DEFAULT package: in petich-postgres-0.1.0.6.jar the
// class sits at the root of the archive with no directory above it. Kotlin cannot import from the
// default package and neither can Java, so no file in a named package can reference it — not by
// import and not by fully qualified name, because it has no qualified name to write. Every
// application puts its own code in a package, so this makes the module's four main classes
// unreachable from all of them.
//
// The tables beside it ARE packaged, and so are the interfaces it implements, which is what makes
// this three lines instead of a reimplementation: construct by name, cast to the packaged interface.
// The alternative was writing our own optimistic lock, outbox batch insert and expiry query, which
// would have been a copy of upstream's that quietly stops matching it.
//
// What it costs: the compiler no longer checks the constructor. A change to its signature becomes a
// runtime failure in PetichStorageTest rather than a red build — which is why that test constructs a
// repository at all rather than mocking one.
//
// Delete this file the moment the package declaration lands upstream.
object PetichRepositories {
    private const val EXPOSED_PETICH_REPOSITORY = "ExposedPetichRepository"

    fun exposed(
        database: Database,
        json: Json,
    ): OutboxAwarePetichRepository {
        val table = PetichTable(json)
        val outbox = OutboxEventsTable()

        val constructor =
            Class
                .forName(EXPOSED_PETICH_REPOSITORY)
                .getConstructor(Database::class.java, PetichTable::class.java, OutboxEventsTable::class.java)

        return constructor.newInstance(database, table, outbox) as OutboxAwarePetichRepository
    }
}
