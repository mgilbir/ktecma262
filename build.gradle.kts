import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.2.0"
    `maven-publish`
    signing
}

group = "io.github.mgilbir"
version = "0.1.3"

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

    // Native targets. The engine is pure common Kotlin, so these need only be
    // declared — there is no native source set and no expect/actual anywhere.
    //
    // Apple targets can only be compiled on a macOS host, and Gradle disables
    // them on any other, so a Linux build stays green while silently producing
    // fewer variants. That is exactly how 0.1.2 shipped without native
    // variants at all; the release workflow now builds on macOS, which can
    // cross-compile the Linux target too, so one host produces the full set.
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()

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

// Publication names, captured once Kotlin has created them. Kotlin creates a
// publication only for targets the host can compile, so this set shrinks on a
// host that cannot build them all.
val publicationNames: SetProperty<String> = objects.setProperty(String::class.java)
afterEvaluate {
    publicationNames.set(publishing.publications.names.toSortedSet())
}

/**
 * Fails when a declared target would not actually be published.
 *
 * The root module lists a variant for every *declared* target no matter which
 * host generated it, but Kotlin creates a publication only for the targets
 * that host can compile — Apple targets need a Mac. Publishing from anywhere
 * else therefore uploads a root module pointing at artifacts that were never
 * built, and nothing goes red until a consumer tries to resolve it.
 *
 * That is how 0.1.2 shipped: built on Linux, with no native variants at all,
 * and immutable on Central by the time anyone noticed.
 *
 * Deliberately not wired into `check`, because on any host other than macOS it
 * is *expected* to fail. The release workflow runs it on macOS, where the full
 * set is buildable and a failure means something is genuinely wrong.
 */
val verifyPublishedVariants by tasks.registering {
    group = "verification"
    description = "Check every declared target will really be published"
    dependsOn("generateMetadataFileForKotlinMultiplatformPublication")

    // Read from the extension at configuration time, so adding a target to the
    // build file extends this check automatically rather than needing a list
    // here that can drift out of date.
    val declaredTargets = kotlin.targets.names.toSortedSet()
    val moduleFile = layout.buildDirectory.file("publications/kotlinMultiplatform/module.json")
    val actual = publicationNames
    inputs.file(moduleFile)

    doLast {
        // The common target's publication is named for the plugin, not the target.
        val expected = declaredTargets.map { if (it == "metadata") "kotlinMultiplatform" else it }
        val present = actual.get()
        check(present.isNotEmpty()) { "no publications at all — the check would pass vacuously" }

        val missing = expected.filterNot { it in present }
        check(missing.isEmpty()) {
            buildString {
                appendLine("no publication for: ${missing.joinToString(", ")}")
                appendLine()
                appendLine("Declared targets: ${declaredTargets.joinToString(", ")}")
                appendLine("Publications:     ${present.joinToString(", ")}")
                appendLine()
                appendLine(
                    "Kotlin creates a publication only for targets the host can compile, " +
                        "but the root module lists a variant for every declared target. " +
                        "Publishing from this host would upload a module referencing " +
                        "artifacts that were never built, and consumers would fail to " +
                        "resolve the dependency.",
                )
                append("Apple targets require a macOS host.")
            }
        }

        // Non-vacuity: the root module must actually carry variants for them.
        @Suppress("UNCHECKED_CAST")
        val parsed = groovy.json.JsonSlurper().parse(moduleFile.get().asFile) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val variants = (parsed["variants"] as? List<Map<String, Any?>>).orEmpty()
        val variantNames = variants.mapNotNull { it["name"] as? String }
        val unlisted = declaredTargets
            .filter { it != "metadata" }
            .filter { target -> variantNames.none { it.startsWith(target) } }
        check(unlisted.isEmpty()) {
            "the root module has no variant for: ${unlisted.joinToString(", ")}"
        }

        logger.lifecycle(
            "verified all ${declaredTargets.size} declared targets are published " +
                "(${variantNames.size} variants)",
        )
    }
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
