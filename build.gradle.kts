plugins {
    kotlin("jvm") version "2.3.21"
    application
}

group = "dev.aidd"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
    implementation("org.alloytools:org.alloytools.alloy.dist:6.2.0") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.aidd.cli.MainKt")
    applicationName = "aidd-tools"
}

tasks.test {
    useJUnitPlatform()
}
