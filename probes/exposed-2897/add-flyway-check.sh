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
        .load()
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
    args((findProperty("url") as String?) ?: "", (findProperty("dir") as String?) ?: "")
    isIgnoreExitValue = true
}
'''
    open(p, "w").write(s)
EOF

docker rm -f fwprobe >/dev/null 2>&1 || true
docker run -d --name fwprobe -e POSTGRES_PASSWORD=pw -p 55432:5432 postgres:18-alpine >/dev/null
for i in $(seq 1 40); do docker exec fwprobe pg_isready -q && break; sleep 1; done
echo "postgres up"
