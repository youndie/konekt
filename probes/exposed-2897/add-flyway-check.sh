set -e
cd ~/expprobe
mkdir -p src/main/kotlin/fw
cat > src/main/kotlin/fw/Main.kt <<'EOF'
package fw

import org.flywaydb.core.Flyway

fun main(args: Array<String>) {
    val flyway = Flyway.configure()
        .dataSource(args[0], "postgres", "pw")
        .locations("filesystem:" + args[1])
        // MATCHED TO THE GENERATOR'S SEPARATOR, and this is not a detail. Flyway recognises a file
        // as a versioned migration only by ITS OWN separator: pointed at files written with "_"
        // while configured with the default "__", it reports "applied 0 migrations" and finds no
        // fault — a run that measures the harness rather than the output.
        .sqlMigrationSeparator(if (args.size > 2 && args[2].isNotEmpty()) args[2] else "__")
        .load()
    // Printed before migrate(), because how Flyway PARSED each name is the question when a version
    // and a separator interact — and a failing migrate() says nothing about it.
    flyway.info().all().forEach { println("SEEN version=" + it.version + " desc=" + it.description) }
    val applied = flyway.migrate()
    println("FLYWAY OK: applied " + applied.migrationsExecuted + " migrations")
}
EOF

python3 - <<'EOF'
p = "build.gradle.kts"
s = open(p).read()
if "flyway-core" not in s:
    s += '''
dependencies {
    implementation("org.flywaydb:flyway-core:13.3.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:13.3.0")
}

tasks.register<JavaExec>("flywayProbe") {
    mainClass.set("fw.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args(
        (findProperty("url") as String?) ?: "",
        (findProperty("dir") as String?) ?: "",
        (findProperty("fwsep") as String?) ?: "",
    )
    isIgnoreExitValue = true
}
'''
    open(p, "w").write(s)
EOF

docker rm -f fwprobe >/dev/null 2>&1 || true
docker run -d --name fwprobe -e POSTGRES_PASSWORD=pw -p 55432:5432 postgres:18-alpine >/dev/null
for i in $(seq 1 40); do docker exec fwprobe pg_isready -q && break; sleep 1; done
echo "postgres up"
