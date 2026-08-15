import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.2.0"
}

group = "io.github.mgilbir"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Java 17 bytecode (class file major version 61) so the library can be consumed
// by JVM modules targeting 17.
val jvmBytecodeTarget = JvmTarget.JVM_17
val expectedClassFileMajor = 61

kotlin {
    // Library: every public declaration must state its visibility and return type.
    explicitApi()

    // Build with a 21 toolchain but emit 17 bytecode. -Xjdk-release additionally
    // limits the visible JDK API surface to 17, so a 21-only signature cannot be
    // linked by accident — jvmTarget alone would not catch that.
    jvmToolchain(21)

    jvm {
        compilerOptions {
            jvmTarget.set(jvmBytecodeTarget)
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }

    // The JS target is not just a deliverable: it is the guard that keeps
    // commonMain free of java.* dependencies, since anything JVM-only fails
    // to compile here.
    js(IR) {
        nodejs {
            testTask {
                // The differential suite replays >20k recorded cases; mocha's
                // 2s default cuts it off long before it finishes.
                useMocha { timeout = "300s" }
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// Live differential fuzzing against a real JavaScript engine. Deliberately not
// wired into `check`: it needs node on PATH and is meant to be run long.
//
//   ./gradlew fuzz -Pcount=200000 -Pseed=7
val fuzz by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fuzz the engine against node and require identical results"
    dependsOn("jvmTestClasses")

    val jvmTest = kotlin.jvm().compilations.getByName("test")
    classpath(jvmTest.output.allOutputs, jvmTest.runtimeDependencyFiles)
    mainClass.set("io.github.mgilbir.ecma262.FuzzMain")

    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) },
    )

    argumentProviders.add {
        listOf(
            (project.findProperty("count") as String?) ?: "20000",
            (project.findProperty("seed") as String?) ?: "1",
            layout.projectDirectory.file("tools/difftest/fuzz-oracle.mjs").asFile.absolutePath,
        )
    }
}

/** Micro-benchmarks, with java.util.regex alongside for scale. `./gradlew bench` */
val bench by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run engine micro-benchmarks"
    dependsOn("jvmTestClasses")

    val jvmTest = kotlin.jvm().compilations.getByName("test")
    classpath(jvmTest.output.allOutputs, jvmTest.runtimeDependencyFiles)
    mainClass.set("io.github.mgilbir.ecma262.BenchmarkMain")

    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) },
    )
}

// Trusting `jvmTarget` is not enough: assert the emitted class files really are
// version 61, so a toolchain or plugin change cannot silently raise the floor.
val verifyJvmBytecodeVersion by tasks.registering {
    dependsOn("compileKotlinJvm")
    val classesDir = layout.buildDirectory.dir("classes/kotlin/jvm/main")
    val expected = expectedClassFileMajor
    inputs.dir(classesDir)
    doLast {
        val root = classesDir.get().asFile
        val offenders = mutableListOf<String>()
        var checked = 0
        root.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
            val head = ByteArray(8)
            f.inputStream().use { require(it.read(head) == 8) { "truncated class file: $f" } }
            val major = ((head[6].toInt() and 0xFF) shl 8) or (head[7].toInt() and 0xFF)
            checked++
            if (major != expected) offenders += "${f.relativeTo(root)} has major $major"
        }
        check(checked > 0) { "no class files found under $root — the check would pass vacuously" }
        check(offenders.isEmpty()) {
            "expected Java 17 bytecode (major $expected):\n" + offenders.joinToString("\n")
        }
        logger.lifecycle("verified $checked class files are Java 17 bytecode (major $expected)")
    }
}

tasks.named("check") {
    dependsOn(verifyJvmBytecodeVersion)
}
