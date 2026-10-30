plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    `java-library`
    id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "io.transxact"
version = "0.1.0"

// Compile with whichever JDK runs Gradle; emit Java 11 bytecode so the library works on 11+.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

repositories { mavenCentral() }

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test { useJUnitPlatform() }

// Single source for the version sent in the User-Agent header.
val sdkVersion = version.toString()
tasks.processResources {
    val v = sdkVersion
    inputs.property("sdkVersion", v)
    filesMatching("transxact-sdk.properties") { expand("version" to v) }
}

// Publishing to Maven Central (Sonatype Central Portal). Credentials and the signing key come from
// ~/.gradle/gradle.properties or ORG_GRADLE_PROJECT_* env vars, never from this repo.
mavenPublishing {
    publishToMavenCentral()
    // Sign only when a key is configured, so local and CI builds work without one.
    if (project.hasProperty("signingInMemoryKey") || project.hasProperty("signing.keyId")) signAllPublications()

    coordinates("io.transxact", "transxact-kotlin", version.toString())

    pom {
        name.set("Transxact Kotlin")
        description.set("Kotlin/JVM client for the Transxact API: hosted checkout for M-PAiSA and MyCash in Fiji.")
        inceptionYear.set("2026")
        url.set("https://github.com/transxactfj/transxact-kotlin")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("transxactfj")
                name.set("Transxact")
                url.set("https://transxact.io")
            }
        }
        scm {
            url.set("https://github.com/transxactfj/transxact-kotlin")
            connection.set("scm:git:git://github.com/transxactfj/transxact-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/transxactfj/transxact-kotlin.git")
        }
    }
}
