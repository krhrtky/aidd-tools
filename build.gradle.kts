import javax.xml.parsers.DocumentBuilderFactory

plugins {
    kotlin("jvm") version "2.3.21"
    application
    jacoco
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
    testImplementation("com.networknt:json-schema-validator:1.0.87")
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
    maxHeapSize = "512m"
}

jacoco {
    toolVersion = "0.8.13"
}

val jacocoXmlReport = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(sourceSets.main.get().output)
    reports {
        html.required.set(true)
        xml.required.set(true)
        xml.outputLocation.set(jacocoXmlReport)
    }
}

val verifyCriticalSourceCoverage = tasks.register("verifyCriticalSourceCoverage") {
    group = "verification"
    description = "Verifies line coverage and class membership for the four corrective critical sources."
    dependsOn(tasks.jacocoTestReport)
    inputs.file(jacocoXmlReport)

    doLast {
        val report = jacocoXmlReport.get().asFile
        val documentFactory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val document = documentFactory.newDocumentBuilder().parse(report)
        val criticalSources = setOf(
            "dev/aidd/backport/BackportService.kt",
            "dev/aidd/cli/AiddCli.kt",
            "dev/aidd/model/ModelValidator.kt",
            "dev/aidd/render/SpecRenderer.kt",
        )
        val requiredClasses = mapOf(
            "dev/aidd/backport/BackportService" to "dev/aidd/backport/BackportService.kt",
            "dev/aidd/backport/BackportDiagnostic" to "dev/aidd/backport/BackportService.kt",
            "dev/aidd/backport/BackportDiagnosticSeverity" to "dev/aidd/backport/BackportService.kt",
            "dev/aidd/backport/DiffResult" to "dev/aidd/backport/BackportService.kt",
            "dev/aidd/cli/AiddCli" to "dev/aidd/cli/AiddCli.kt",
            "dev/aidd/cli/ExplorationArtifacts" to "dev/aidd/cli/AiddCli.kt",
            "dev/aidd/cli/Arguments" to "dev/aidd/cli/AiddCli.kt",
            "dev/aidd/cli/CliException" to "dev/aidd/cli/AiddCli.kt",
            "dev/aidd/model/ModelValidator" to "dev/aidd/model/ModelValidator.kt",
            "dev/aidd/model/Diagnostic" to "dev/aidd/model/ModelValidator.kt",
            "dev/aidd/model/Severity" to "dev/aidd/model/ModelValidator.kt",
            "dev/aidd/model/ValidationResult" to "dev/aidd/model/ModelValidator.kt",
            "dev/aidd/render/SpecRenderer" to "dev/aidd/render/SpecRenderer.kt",
        )

        val sourceCounters = mutableMapOf<String, Pair<Long, Long>>()
        val sourceNodes = document.getElementsByTagName("sourcefile")
        for (index in 0 until sourceNodes.length) {
            val source = sourceNodes.item(index) as org.w3c.dom.Element
            val packageName = (source.parentNode as org.w3c.dom.Element).getAttribute("name")
            val sourceKey = "$packageName/${source.getAttribute("name")}"
            if (sourceKey !in criticalSources) continue
            val counters = source.getElementsByTagName("counter")
            val lineCounter = (0 until counters.length)
                .map { counters.item(it) as org.w3c.dom.Element }
                .singleOrNull { it.getAttribute("type") == "LINE" }
                ?: throw GradleException("Missing LINE counter for critical source $sourceKey")
            sourceCounters[sourceKey] =
                lineCounter.getAttribute("covered").toLong() to lineCounter.getAttribute("missed").toLong()
        }
        val missingSources = criticalSources - sourceCounters.keys
        if (missingSources.isNotEmpty()) {
            throw GradleException("Missing critical sourcefile elements: ${missingSources.sorted().joinToString()}")
        }

        val classSources = mutableMapOf<String, String>()
        val classNodes = document.getElementsByTagName("class")
        for (index in 0 until classNodes.length) {
            val classElement = classNodes.item(index) as org.w3c.dom.Element
            val packageName = (classElement.parentNode as org.w3c.dom.Element).getAttribute("name")
            classSources[classElement.getAttribute("name")] =
                "$packageName/${classElement.getAttribute("sourcefilename")}"
        }
        requiredClasses.forEach { (className, expectedSource) ->
            val actualSource = classSources[className]
                ?: throw GradleException("Missing required JaCoCo class $className")
            if (actualSource != expectedSource) {
                throw GradleException(
                    "JaCoCo class $className maps to $actualSource; expected $expectedSource",
                )
            }
        }

        val covered = sourceCounters.values.sumOf(Pair<Long, Long>::first)
        val missed = sourceCounters.values.sumOf(Pair<Long, Long>::second)
        val total = covered + missed
        if (total == 0L) {
            throw GradleException("Critical source LINE coverage denominator is zero")
        }
        val ratio = covered.toDouble() / total
        if (ratio < 0.80) {
            throw GradleException(
                "Critical source LINE coverage is ${"%.2f".format(ratio * 100)}% " +
                    "($covered/$total); expected at least 80.00%",
            )
        }
        logger.lifecycle(
            "Critical source LINE coverage: ${"%.2f".format(ratio * 100)}% ($covered/$total)",
        )
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(verifyCriticalSourceCoverage)
}

tasks.check {
    dependsOn(verifyCriticalSourceCoverage)
}
