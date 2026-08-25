# Build a branch of Exposed's migration plugin and point the probe at it.
#
# Run on the Linux box. Takes the fork and branch as $1 and $2:
#
#   ~/.claude/bin/wsl-run "$(cat probes/exposed-2897/verify-a-patch.sh)" cfcromn fix/2897-migration-file-collision
#
# Three things about Exposed's own build cost a run each when they were discovered:
#
#   * its Gradle is 8.14.4, which REFUSES to run on JDK 25 — and says so as a bare "25.0.3" with no
#     sentence around it. JDK 21 is on the box and is what this uses;
#   * it wants a JDK 17 TOOLCHAIN for exposed-core, which is not installed. The foojay resolver
#     fetches one, and its `plugins` block has to go AFTER `pluginManagement` or Gradle refuses the
#     settings file outright;
#   * the plugin modules carry their OWN version, independent of the root `version` property. Setting
#     the root one makes the plugin ask for `exposed-jdbc` at a version nobody published; setting the
#     module ones leaves it asking for the released libraries, which is what we want — the changed
#     code is the plugin.
set -e
FORK=${1:?fork, e.g. cfcromn}
BRANCH=${2:?branch}
VERSION=${3:-1.4.0-patch}

rm -rf ~/exposed-pr
git clone --depth 20 --branch "$BRANCH" "https://github.com/$FORK/Exposed.git" ~/exposed-pr
cd ~/exposed-pr

python3 - "$VERSION" <<'PY'
import sys
version = sys.argv[1]

p = "settings.gradle.kts"; s = open(p).read()
if "foojay" not in s:
    s = s.replace("dependencyResolutionManagement {",
                  'plugins {\n    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"\n}\n\ndependencyResolutionManagement {', 1)
    open(p, "w").write(s)

for module in ("exposed-gradle-plugin", "exposed-plugin-core"):
    p = f"{module}/build.gradle.kts"; s = open(p).read()
    open(p, "w").write(s.replace('version = "1.4.0"', f'version = "{version}"', 1))
PY

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew :exposed-plugin-core:publishToMavenLocal :exposed-gradle-plugin:publishToMavenLocal -x test --console=plain

# The probe resolves plugins from mavenLocal FIRST, and names the patched version explicitly rather
# than shadowing the released one — a build published over `1.4.0` in mavenLocal is a patch wearing
# the release's name, and nothing downstream can tell which it got.
cd ~/expprobe
sed -i 's|pluginManagement { repositories { |pluginManagement { repositories { mavenLocal(); |' settings.gradle.kts
sed -i 's|dependencyResolutionManagement { repositories { |dependencyResolutionManagement { repositories { mavenLocal(); |' settings.gradle.kts
sed -i "s|id(\"org.jetbrains.exposed.plugin\") version \"[^\"]*\"|id(\"org.jetbrains.exposed.plugin\") version \"$VERSION\"|" build.gradle.kts

echo "probe now on $VERSION — run the shapes from README.md"
