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
    // The tests read the shared examples, and fsm-cli's test cases and schemas (copied into plugin/cli by scripts/fetch-cli.sh).
    systemProperty("fsm.examples", rootProject.file("examples").absolutePath)
    systemProperty("fsm.cases", rootProject.file("plugin/cli/cases").absolutePath)
    systemProperty("fsm.schemas", rootProject.file("plugin/cli/schemas").absolutePath)
}
