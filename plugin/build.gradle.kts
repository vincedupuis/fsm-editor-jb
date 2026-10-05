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
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
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
        }
    }
}

// The fsm code generator copied by scripts/fetch-cli.* (gitignored) ships in the plugin's cli/ folder.
val cliDir = rootProject.layout.projectDirectory.dir("plugin/cli")
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareSandbox") {
    from(cliDir) {
        into(intellijPlatform.projectName.map { "$it/cli" })
    }
}

tasks.test {
    systemProperty("fsm.examples", rootProject.file("examples").absolutePath)
    systemProperty("fsm.cli", cliDir.asFile.absolutePath)
    systemProperty("fsm.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}
