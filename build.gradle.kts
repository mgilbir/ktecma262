import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.2.0"
    `maven-publish`
    signing
}

group = "io.github.mgilbir"
version = "0.1.2"

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

// ----------------------------------------------------------------- publishing

// Maven Central requires a javadoc artifact. The API documentation lives in
// KDoc on the source, which is published in the sources jar Kotlin Multiplatform
// generates, so this is a placeholder rather than a second copy of the docs.
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)
        pom {
            name.set("ktecma262")
            description.set(
                "An ECMA-262 (JavaScript) regular expression engine in pure Kotlin, " +
                    "for Kotlin Multiplatform.",
            )
            url.set("https://github.com/mgilbir/ktecma262")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://github.com/mgilbir/ktecma262/blob/main/LICENSE")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("mgilbir")
                    name.set("Miguel Eduardo Gil Biraud")
                    url.set("https://github.com/mgilbir")
                }
            }
            scm {
                url.set("https://github.com/mgilbir/ktecma262")
                connection.set("scm:git:https://github.com/mgilbir/ktecma262.git")
                developerConnection.set("scm:git:ssh://git@github.com/mgilbir/ktecma262.git")
            }
        }
    }

    repositories {
        maven {
            name = "central"
            // Overridable so the same build can target a staging repository.
            url = uri(
                providers.gradleProperty("centralRepositoryUrl").orNull
                    ?: "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/",
            )
            credentials {
                // Read from the environment only — never stored in the repository.
                // Names match the GitHub repository secrets exactly, so there is
                // one set of names to keep straight rather than two.
                // Trimmed: a token pasted into a secret often carries a trailing
                // newline, which the server rejects as a bad credential.
                username = providers.environmentVariable("CENTRAL_TOKEN_USERNAME").orNull?.trim()
                password = providers.environmentVariable("CENTRAL_TOKEN_PASSWORD").orNull?.trim()
            }
        }
    }
}

signing {
    // Signing is required to publish to Maven Central and irrelevant locally, so
    // it switches itself on only when a key is supplied. The key is an
    // ASCII-armoured private key read straight from the environment; it is never
    // written to disk or into a Gradle property.
    val signingKey = providers.environmentVariable("MAVEN_GPG_PRIVATE_KEY").orNull
    val signingPassword = providers.environmentVariable("MAVEN_GPG_PASSPHRASE").orNull?.trim()
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}

// Gradle cannot infer that each publication's signing task needs the shared
// javadoc jar to exist first.
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(tasks.withType<Sign>())
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
