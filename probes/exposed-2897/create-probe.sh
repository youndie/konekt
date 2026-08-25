set -e
rm -rf ~/expprobe
mkdir -p ~/expprobe/src/main/kotlin/p3 ~/expprobe/src/main/kotlin/p4 ~/expprobe/src/main/kotlin/pflat
cd ~/expprobe
cp -r ~/konekt/gradle ~/konekt/gradlew ~/konekt/gradlew.bat .
rm -f gradle/libs.versions.toml

cat > settings.gradle.kts <<'EOF'
rootProject.name = "expprobe"
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
EOF

cat > gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx2g
EOF

cat > build.gradle.kts <<'EOF'
import org.jetbrains.exposed.v1.plugin.core.migration.VersionFormat

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.exposed.plugin") version "1.4.0"
}

kotlin { jvmToolchain(25) }

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.exposed:exposed-core:1.4.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.4.0")
    implementation("org.jetbrains.exposed:exposed-migration-jdbc:1.4.0")
    implementation("org.postgresql:postgresql:42.7.13")
}

val pkg = (findProperty("pkg") as String?) ?: "p3"
val out = (findProperty("out") as String?) ?: "gen"
val fmt = (findProperty("fmt") as String?)
// The plugin's own separator. Settable because the generated version and the separator
// interact: an index appended with the same character the separator uses is not
// distinguishable from it, and Flyway reads the version up to the FIRST separator.
val sep = (findProperty("sep") as String?) ?: "__"

exposed {
    migrations {
        tablesPackage.set(pkg)
        testContainersImageName.set("postgres:18-alpine")
        fileDirectory.set(layout.buildDirectory.dir(out))
        filePrefix.set("V"); fileSeparator.set(sep); fileExtension.set(".sql")
        if (fmt != null) fileVersionFormat.set(VersionFormat.valueOf(fmt))
    }
}
EOF

# --- p3: the issue's shape — parent + two children of it
cat > src/main/kotlin/p3/Tables.kt <<'EOF'
package p3

import org.jetbrains.exposed.v1.core.Table

object ParentTable : Table("p_parent") {
    val id = varchar("id", 16)
    override val primaryKey = PrimaryKey(id)
}
object ChildOneTable : Table("p_child_one") {
    val id = varchar("id", 16)
    val parentId = varchar("parent_id", 16).references(ParentTable.id)
    override val primaryKey = PrimaryKey(id)
}
object ChildTwoTable : Table("p_child_two") {
    val id = varchar("id", 16)
    val parentId = varchar("parent_id", 16).references(ParentTable.id)
    override val primaryKey = PrimaryKey(id)
}
EOF

# --- p4: one INDEPENDENT table beside the same parent+two children
cat > src/main/kotlin/p4/Tables.kt <<'EOF'
package p4

import org.jetbrains.exposed.v1.core.Table

object AlphaTable : Table("q_alpha") {
    val id = varchar("id", 16)
    override val primaryKey = PrimaryKey(id)
}
object ParentTable : Table("q_parent") {
    val id = varchar("id", 16)
    override val primaryKey = PrimaryKey(id)
}
object ChildOneTable : Table("q_child_one") {
    val id = varchar("id", 16)
    val parentId = varchar("parent_id", 16).references(ParentTable.id)
    override val primaryKey = PrimaryKey(id)
}
object ChildTwoTable : Table("q_child_two") {
    val id = varchar("id", 16)
    val parentId = varchar("parent_id", 16).references(ParentTable.id)
    override val primaryKey = PrimaryKey(id)
}
EOF

# --- pflat: three tables with no foreign keys at all
cat > src/main/kotlin/pflat/Tables.kt <<'EOF'
package pflat

import org.jetbrains.exposed.v1.core.Table

object AlphaTable : Table("f_alpha") {
    val id = varchar("id", 16)
    override val primaryKey = PrimaryKey(id)
}
object BetaTable : Table("f_beta") {
    val id = varchar("id", 16)
    override val primaryKey = PrimaryKey(id)
}
object GammaTable : Table("f_gamma") {
    val id = varchar("id", 16)
    override val primaryKey = PrimaryKey(id)
}
EOF

echo "OK: probe created at ~/expprobe"
find ~/expprobe/src -type f
