import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        // The IDE to compile and test against: platformVersion (the oldest supported), or -PplatformVersion=<newer>,
        // as CI tests the newest. IntelliJ IDEA Community isn't published since 2025.3: IntelliJ IDEA from then on.
        val platform = providers.gradleProperty("platformVersion").get()
        if (platform.substringBefore('.').toInt() * 10 + platform.substringAfter('.').substringBefore('.').toInt() >= 20253) {
            intellijIdea(platform)
            // XML support (old files, XMI) and VCS (the merge of a file with conflicts): plugins of their own there too.
            bundledPlugin("com.intellij.xml")
            bundledPlugin("com.intellij.platform.vcs")
        } else {
            intellijIdeaCommunity(platform)
        }
        // JSON support (the Text tab of .fsm and .fsm.layout files, their schemas): a plugin of its own since 2024.3.
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        apiVersion = KotlinVersion.KOTLIN_2_0
        // Implementations of the platform's Kotlin interfaces use their default methods, without bridges.
        freeCompilerArgs.addAll("-Xsuppress-version-warnings", "-jvm-default=no-compatibility")
    }
}

intellijPlatform {
    // Names the plugin folder and build/distributions/fsm-editor-<version>.zip.
    projectName = "fsm-editor"
    pluginConfiguration {
        id = "com.vincegosoftware.fsmeditor"
        name = "FSM Editor - UML State Machines & Code Generation"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    // publishPlugin uploads to the JetBrains Marketplace (see .github/workflows/release.yml).
    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }
    buildSearchableOptions = false
    instrumentCode = false
    pluginVerification {
        ides {
            // The oldest supported IDE, and optionally a local one: -PverifyIde=/path/to/IntelliJ IDEA.app
            create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion"))
            providers.gradleProperty("verifyIde").orNull?.let { local(file(it)) }
            // -PverifyAll (CI): the IDEs of verifyIdes too.
            if (providers.gradleProperty("verifyAll").isPresent) {
                for (ide in providers.gradleProperty("verifyIdes").get().split(' ').filter { it.isNotBlank() }) {
                    create(IntelliJPlatformType.valueOf(ide.substringBefore(':')), ide.substringAfter(':'))
                }
            }
        }
    }
}

// fsm-editor-common's sidecar, in plugin/cli/<platform>, fsm-cli's templates, in plugin/cli/templates, and the files'
// JSON Schemas, in plugin/cli/schemas (copied by scripts/fetch-common.sh, or unpacked from fsm-editor-common's release
// archives by the release workflow; gitignored), ship in the plugin's cli/ folder. The interface's cases
// (plugin/cli/api-cases) are for the tests only.
val cliDir = rootProject.layout.projectDirectory.dir("plugin/cli")
// Also in the sandboxes of the tests: the plugin laid out as installed (FsmSchemas finds cli/schemas beside its lib/).
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    from(cliDir) {
        exclude("api-cases/**", "schemas/api.schema.json")
        into(intellijPlatform.projectName.map { "$it/cli" })
    }
}

tasks.test {
    // The assertion's message and the stack, in the build's output (CI shows only that).
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty("fsm.examples", rootProject.file("examples").absolutePath)
    systemProperty("fsm.cli", cliDir.asFile.absolutePath)
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
    systemProperty("fsm.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}
