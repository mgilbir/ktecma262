plugins {
    // Lets Gradle download a matching JDK when the toolchain below isn't installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "ktecma262"
