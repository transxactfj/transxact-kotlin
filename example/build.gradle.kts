plugins {
    kotlin("jvm") version "2.2.0"
    application
}

repositories { mavenCentral() }

dependencies {
    // Replaced by the parent folder's source through includeBuild in settings.gradle.kts.
    implementation("io.transxact:transxact-kotlin:0.1.0")
    implementation("io.ktor:ktor-server-netty:3.1.3")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

application { mainClass.set("MainKt") }
