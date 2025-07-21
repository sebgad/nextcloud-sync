plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "1.9.24"
    id("org.jlleitschuh.gradle.ktlint") version "12.3.0"
}

group = "org.nextclouddav"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation("io.github.pdvrieze.xmlutil:serialization:0.84.3")
    implementation("io.github.pdvrieze.xmlutil:core:0.84.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
