// import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "org.austindroids"
version = "0.5.0"

val jacksonver = "3.2.0"
val testcontainerver = "2.0.5"
val mockitoKotlinVer = "6.3.0"

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotest)
    `maven-publish`
    `java-library`
    application
    id("com.gradleup.shadow") version "9.4.2"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("org.jetbrains.dokka") version "2.2.0"
    id("jacoco")
}

val kotlinVersion: String = libs.versions.kotlin.get()
val kotestVersion: String = libs.versions.kotest.get()

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("jakarta.validation:jakarta.validation-api:3.0.2")

    // Maven Plugin API
    implementation("org.apache.maven:maven-plugin-api:3.9.16")
    implementation("org.apache.maven.plugin-tools:maven-plugin-annotations:3.15.2")

    // YAML Processing
    implementation("org.yaml:snakeyaml:2.6")
    // JSON Schema validation
    implementation("com.networknt:json-schema-validator:3.0.4")

    // Jackson 3.x — BOM manages all versions
    implementation(platform("tools.jackson:jackson-bom:$jacksonver"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.core:jackson-core")

    // Jackson Kotlin Module (for Kotlin interop)
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Jackson YAML Module
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    // Jackson Java 8 Time module — use 2.x version (compatible with 3.x)
    //implementation("tools.jackson.datatype:jackson-datatype-jsr310")  // version from BOM


    // CSV
    implementation("com.jsoizo:kotlin-csv-jvm:2.0.0")

    // Database & ORM
    implementation("org.ktorm:ktorm-core:4.1.1")
    implementation("org.ktorm:ktorm-support-postgresql:4.1.1")
    implementation("org.postgresql:postgresql:42.7.11")

    // SQL formatting
    implementation("com.github.vertical-blank:sql-formatter:2.0.5")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.18")
    // implementation("ch.qos.logback:logback-classic:1.5.34")


    // CLI

    // --- Clikt (command parsing) ---
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    // optional: markdown rendering in help messages
    // implementation("com.github.ajalt.clikt:clikt-markdown:5.1.0")

    // --- Mordant (terminal output) ---
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    // optional: coroutine-based animations (progress bars, spinners, etc.)
    // implementation("com.github.ajalt.mordant:mordant-coroutines:3.0.2")
    // optional: render Markdown in terminal output
    // implementation("com.github.ajalt.mordant:mordant-markdown:3.0.2")


    //testImplementation(kotlin("test"))
    //implementation(platform("org.junit:junit-bom:$junitver"))
    // testImplementation("org.junit.jupiter:junit-jupiter")
    // testImplementation("org.junit.jupiter:junit-jupiter-params")
    // testImplementation("org.mockito.kotlin:mockito-kotlin:$mockitoKotlinVer")
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest:kotest-property:$kotestVersion")
    // Mocking
    testImplementation("io.mockk:mockk:1.14.11")

    // Coroutine testing
    // testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")

    // Flow testing (if using Kotlin Flow)
    // testImplementation("app.cash.turbine:turbine:1.2.0")

    // Instead of testcontainers-junit-jupiter:
    // testImplementation("io.kotest:kotest-extensions-testcontainers:${junitver}")

    // testImplementation("org.testcontainers:testcontainers:$testcontainerver")
    // testImplementation("org.testcontainers:testcontainers-postgresql:$testcontainerver")
    // testImplementation("org.testcontainers:testcontainers-junit-jupiter:$testcontainerver")
}

kotlin {
    jvmToolchain(24) // use JDK 24 to compile
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) // but produce Java 17 bytecode
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    inputs.property("appName", rootProject.name)
    filesMatching("version.properties") {
        expand(mapOf(
            "version" to project.version.toString(),
            "appName" to rootProject.name
        ))
    }
}

application {
    mainClass.set("org.austindroids.MainKt")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "org.austindroids.MainKt",
            "Implementation-Version" to project.version
        )
    }
}

tasks.shadowJar {
    // The main class is auto-detected from your existing `application` block
    archiveClassifier.set("")

    // Merge service descriptor files instead of overwriting
    mergeServiceFiles()

    // Exclude signature files from dependency JARs (they break fat JARs)
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

    // Relocate if you ever have classpath conflicts:
    // relocate("com.fasterxml.jackson", "shadow.com.fasterxml.jackson")
}


tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

kotest {customGradleTask = true}

dokka {
    moduleName.set("Knoppen")
}

tasks.register<Jar>("dokkaJar") {
    description = "dokkaJar"
    dependsOn(tasks.named("dokkaHtml"))
    archiveClassifier.set("javadoc")
    from(layout.buildDirectory.dir("dokka/html"))
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifact(tasks.shadowJar)
            artifact(tasks["dokkaJar"])
            pom {
                name.set("Knoppen Maven Plugin")
                description.set("A Maven plugin that generates SQL from YAML configuration")
                url.set("https://github.com/dutchmo/Knoppen")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("dutch")
                        name.set("Dutch Matous")
                        email.set("gregory.matous@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/dutchmo/Knoppen.git")
                    developerConnection.set("scm:git:ssh://github.com/dutchmo/Knoppen.git")
                    url.set("https://github.com/dutchmo/Knoppen")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/dutchmo/Knoppen")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
        maven {
            name = "Nexus"
            url = uri("https://nexus-server/repository/maven-releases/")
            credentials {
                username = System.getenv("NEXUS_USERNAME")
                password = System.getenv("NEXUS_PASSWORD")
            }
        }
    }
}
