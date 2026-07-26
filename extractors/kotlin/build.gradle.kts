plugins {
    kotlin("jvm") version "2.3.21"
    application
}

group = "dev.aidd"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.21")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.1")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

application {
    mainClass.set("dev.aidd.extractor.kotlin.MainKt")
    applicationName = "aidd-kotlin-extractor"
}

tasks.test {
    useJUnitPlatform()
}
