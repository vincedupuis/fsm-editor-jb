import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm")
}

// Core has no IntelliJ dependency: the IDE provides the Kotlin standard library at run time,
// tests get it from Maven Central.
dependencies {
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // The IDE's bundled standard library: 2024.3 ships Kotlin 2.0.
        apiVersion = KotlinVersion.KOTLIN_2_0
        // Implementations of the platform's Kotlin interfaces use their default methods, without bridges.
        freeCompilerArgs.addAll("-Xsuppress-version-warnings", "-jvm-default=no-compatibility")
    }
}

tasks.test {
    useJUnitPlatform()
    // The tests read the shared examples, and run fsm-editor-common's sidecar and its interface's cases (copied into
    // plugin/cli by scripts/fetch-common.sh).
    systemProperty("fsm.examples", rootProject.file("examples").absolutePath)
    systemProperty("fsm.cli", rootProject.file("plugin/cli").absolutePath)
    // The VS Code edition's geometry fixtures and their reference SVGs (its test/unit/fixtures/geometry).
    systemProperty("fsm.geometry", file("src/test/geometry").absolutePath)
    inputs.dir("src/test/geometry")
    // The VS Code edition's clipboard fixtures (its test/unit/fixtures/clipboard).
    systemProperty("fsm.clipboard", file("src/test/clipboard").absolutePath)
    inputs.dir("src/test/clipboard")
    // The VS Code edition's editing fixtures (its test/unit/fixtures/editing).
    systemProperty("fsm.editing", file("src/test/editing").absolutePath)
    inputs.dir("src/test/editing")
    // The listing (ListingTest): the plugin's descriptor and build script, the README and the docs it links to.
    systemProperty("fsm.root", rootProject.projectDir.absolutePath)
    inputs.files(rootProject.files("plugin/src/main/resources/META-INF/plugin.xml", "plugin/build.gradle.kts", "README.md"))
    inputs.dir(rootProject.file("docs"))
    // The sidecar of this machine (plugin/cli/<platform>/, as Common.platform names it), when scripts/fetch-common.sh
    // copied it: from a release's archives, it copies every platform's.
    val platform = System.getProperty("os.name").lowercase().let {
        when {
            it.startsWith("windows") -> "windows"
            it.startsWith("mac") -> "darwin"
            else -> "linux"
        }
    } + "-" + (if (System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }) "arm64" else "x64")
    fileTree(rootProject.file("plugin/cli/$platform")).matching { include("fsm-editor-common*") }.files.firstOrNull()
        ?.let { systemProperty("fsm.common", it.absolutePath) }
}
