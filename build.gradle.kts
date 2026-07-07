// import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "org.austindroids"
version = "0.5.0"

val jacksonver = "3.2.0"

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

// CLI presentation dependencies (Clikt + transitively Mordant) — compile-only so they never
// leak into the published mavenPlugin POM. Re-added at runtime only for the shadow (CLI) jar.
val cliOnly: Configuration by configurations.creating

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Jakarta bean validation (JSR-380)
    // implementation("jakarta.validation:jakarta.validation-api:3.0.2")
    // implementation("org.hibernate.validator:hibernate-validator:9.1.1.Final")

    // Maven Plugin API
    implementation("org.apache.maven:maven-plugin-api:3.9.16")
    implementation("org.apache.maven.plugin-tools:maven-plugin-annotations:3.15.2")

    // JSON Schema validation
    implementation("com.networknt:json-schema-validator:3.0.4")

    // Jackson 3.x — BOM manages all versions
    implementation(platform("tools.jackson:jackson-bom:$jacksonver"))
    implementation("tools.jackson.core:jackson-core")
    implementation("tools.jackson.core:jackson-databind") // ObjectMapper

    // Jackson YAML Module (YAML Factory)
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")

    // Jackson Kotlin Module (Serde Kotlin classes)
    implementation("tools.jackson.module:jackson-module-kotlin")

    // CSV
    implementation("com.jsoizo:kotlin-csv-jvm:2.0.0")

    // Database & ORM (unused)
    // implementation("org.ktorm:ktorm-core:4.1.1")
    // implementation("org.ktorm:ktorm-support-postgresql:4.1.1")

    // Logging — SLF4J API for compilation; logback as runtime-only so it is
    // bundled in the standalone fat JAR but stripped from the plugin POM via withXml.
    implementation("org.slf4j:slf4j-api:2.0.18")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.34")

    // CLI — compileOnly (via cliOnly, see below); the maven plugin artifact never needs it.
    cliOnly("com.github.ajalt.clikt:clikt:5.1.0") {
        exclude("com.github.ajalt.mordant", "mordant-jvm-jna")
    }

    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest:kotest-property:$kotestVersion")
    testImplementation("io.mockk:mockk:1.14.11")

    // Maven plugin integration tests (JUnit5 @MojoTest harness). The harness needs a
    // Maven runtime (core + compat) on the test classpath; junit-jupiter drives the
    // plain @Test harness tests alongside the Kotest specs on the JUnit platform.
    testImplementation("org.apache.maven.plugin-testing:maven-plugin-testing-harness:3.5.1")
    testImplementation("org.apache.maven:maven-core:3.9.16")
    testImplementation("org.apache.maven:maven-compat:3.9.16")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    // AbstractMojoTestCase extends JUnit3 junit.framework.TestCase; provide it (tests
    // themselves are driven by JUnit5 @Test, we only subclass for its lookup helpers).
    testImplementation("junit:junit:4.13.2")
}

configurations.compileOnly.get().extendsFrom(cliOnly)

kotlin {
    jvmToolchain(24)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xcollection-literals", "-Xreturn-value-checker=full")
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
        expand(
            mapOf(
                "version" to project.version.toString(),
                "appName" to rootProject.name,
            ),
        )
    }
    // plugin.xml is full of its own ${...} Maven placeholders (${project.basedir}, ${configFile}, ...)
    // that must survive untouched, so use Ant-style @token@ replacement instead of expand()'s ${...} syntax.
    filesMatching("META-INF/maven/plugin.xml") {
        filter<org.apache.tools.ant.filters.ReplaceTokens>(
            "tokens" to mapOf("version" to project.version.toString()),
        )
    }
}

application {
    mainClass.set("org.austindroids.MainKt")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "org.austindroids.MainKt",
            "Implementation-Version" to project.version,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Standalone fat JAR — bundles everything including logback.
//  Classifier "standalone" keeps the regular JAR as the primary
//  java component artifact, used by the mavenPlugin publication.
// ═══════════════════════════════════════════════════════════════════
tasks.shadowJar {
    // Add cliOnly (Clikt/Mordant) alongside the normal runtime deps — compileOnly
    // dependencies are otherwise invisible to shadowJar, which shades runtimeClasspath by default.
    configurations = listOf(project.configurations.runtimeClasspath.get(), cliOnly)
    archiveClassifier.set("standalone")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes(
            "Main-Class" to "org.austindroids.MainKt",
            "Implementation-Version" to project.version,
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}

// Make distribution & script tasks wait for the shadow JAR
afterEvaluate {
    tasks.matching { it.name.startsWith("startShadowScripts") }.configureEach {
        dependsOn(tasks.jar)
    }
    tasks.matching { it.name.startsWith("distShadow") }.configureEach {
        dependsOn(tasks.shadowJar)
    }
    tasks.named("distZip") { dependsOn(tasks.shadowJar) }
    tasks.named("distTar") { dependsOn(tasks.shadowJar) }
    tasks.named("startScripts") { dependsOn(tasks.shadowJar) }
}

// Note: the plain `run` task uses runtimeClasspath, which no longer carries Clikt/Mordant
// (compileOnly). Use `./gradlew runShadow` (auto-provided by the shadow + application plugins)
// to run the CLI during development.

tasks.test {
    useJUnitPlatform()
    // The plugin-testing-harness resolves mojos by groupId:artifactId:version:goal;
    // expose the project version so MojoHarnessTest can build the matching role hint.
    systemProperty("knoppen.version", project.version.toString())
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

kotest { customGradleTask = true }

dokka {
    moduleName.set("Knoppen")
}

tasks.register<Jar>("dokkaJar") {
    description = "dokkaJar"
    dependsOn(tasks.named("dokkaHtml"))
    archiveClassifier.set("javadoc")
    from(layout.buildDirectory.dir("dokka/html"))
}

// ═══════════════════════════════════════════════════════════════════
//  Publishing — two publications, same repositories
//
//  mavenPlugin  — thin JAR (compiled classes only); POM lists all
//                 implementation deps so Maven resolves them at
//                 plugin execution time. Logback is stripped from
//                 the POM so Maven never downloads it — Maven
//                 provides its own SLF4J binding at runtime.
//
//  standaloneCli — fat JAR, fully self-contained including logback.
// ═══════════════════════════════════════════════════════════════════
publishing {
    publications {
        create<MavenPublication>("mavenPlugin") {
            from(components["java"])
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
                // Strip logback from the plugin POM — Maven provides its own SLF4J
                // binding at runtime, so downloading logback would cause conflicts.
                // Node.get(String) is used for namespace-safe child lookup; name()
                // returns a QName (not String) when the POM carries xmlns.
                withXml {
                    @Suppress("UNCHECKED_CAST")
                    val depsNode =
                        (asNode().get("dependencies") as groovy.util.NodeList)
                            .firstOrNull() as? groovy.util.Node ?: return@withXml
                    @Suppress("UNCHECKED_CAST")
                    (depsNode.get("dependency") as groovy.util.NodeList)
                        .filterIsInstance<groovy.util.Node>()
                        .filter { dep ->
                            @Suppress("UNCHECKED_CAST")
                            (
                                (dep.get("groupId") as groovy.util.NodeList)
                                    .firstOrNull() as? groovy.util.Node
                            )?.text() == "ch.qos.logback"
                        }.toList()
                        .forEach { depsNode.remove(it) }
                }
            }
        }

        create<MavenPublication>("standaloneCli") {
            artifact(tasks.shadowJar)
            pom {
                name.set("Knoppen CLI")
                description.set("Standalone CLI tool that generates SQL from YAML configuration")
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

tasks.named("publish") {
    dependsOn(tasks.shadowJar)
}
