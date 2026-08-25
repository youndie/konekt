package io.konekt.db

import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A gate on the migration FILES, separate from the gate on what they produce.
//
// It exists because of how they are drafted. `generateMigrations` names a file by a version stamped
// to the second plus a description taken from its first statement, so a run that touches several
// tables writes several files with one name — they overwrite each other and a table goes missing
// silently (JetBrains/Exposed#2897). Even when nothing is lost, the shared version is a set Flyway
// refuses outright: `Found more than one migration with version …`, verified against 13.3.0.
//
// So every draft has to be renumbered by hand before it is committed, and this is what notices when
// somebody forgets. It reads the directory rather than the classpath on purpose: a duplicate version
// is a property of the files, and Flyway would only complain at deploy time.
class MigrationFilesTest {
    private val migrations = Path("src/main/resources/db/migration")

    private val versioned = Regex("""^V(\d+(?:[._]\d+)*)__(.+)\.sql$""")

    private fun files() =
        migrations
            .listDirectoryEntries()
            .filter { !it.isDirectory() }
            .map { it.name }
            .sorted()

    @Test
    fun `every migration file is named the way flyway expects`() {
        files().forEach { name ->
            assertTrue(versioned.matches(name), "$name is not a Flyway versioned migration")
        }
    }

    @Test
    fun `no two migrations share a version`() {
        val versions = files().mapNotNull { versioned.find(it)?.groupValues?.get(1) }

        assertEquals(
            versions.size,
            versions.toSet().size,
            "duplicate migration versions — Flyway refuses the whole set:\n" +
                versions
                    .groupBy { it }
                    .filterValues { it.size > 1 }
                    .keys
                    .joinToString("\n"),
        )
    }

    @Test
    fun `there are migrations to check`() {
        // The guard on the guard: both assertions above pass on an empty directory, and a wrong
        // path would produce exactly that.
        assertTrue(files().size >= 2, "found ${files().size} migrations — is the path right?")
    }
}
