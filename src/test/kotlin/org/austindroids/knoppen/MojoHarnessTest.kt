package org.austindroids.knoppen

import org.apache.maven.plugin.testing.AbstractMojoTestCase
import org.austindroids.knoppen.mojos.GenerateMojo
import org.austindroids.knoppen.mojos.ValidateMojo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Layer-2 (maven-plugin-testing-harness) tests. Unlike the direct-instantiation
 * Kotest tests, these exercise the *plugin descriptor + POM wiring*: the mojo is
 * looked up by goal from `META-INF/maven/plugin.xml` and configured from a stub
 * POM's `<configuration>` block, exactly as Maven would at build time.
 *
 * The 3.5.1 JUnit5 `@MojoTest` extension targets the Maven 4 API and cannot
 * instantiate our Maven-3 `AbstractMojo` mojos, so we use `AbstractMojoTestCase`
 * (which does), driven from JUnit5 lifecycle methods.
 */
class MojoHarnessTest : AbstractMojoTestCase() {

    // Gradle builds the (token-filtered) descriptor to build/resources/main, not
    // the Maven-default target/classes; point the harness there.
    override fun getPluginDescriptorPath(): String =
        File("build/resources/main/META-INF/maven/plugin.xml").absolutePath

    @BeforeEach
    public override fun setUp() {
        super.setUp()
    }

    // groupId/artifactId match the plugin descriptor; version is passed by the Gradle
    // test task so the harness role hint (groupId:artifactId:version:goal) resolves.
    private val groupId = "org.austindroids"
    private val artifactId = "knoppen"
    private val version: String = System.getProperty("knoppen.version")

    /** Looks up a mojo by goal, taking its `<configuration>` from the given stub POM. */
    private fun lookupFromPom(goal: String, pomPath: String): org.apache.maven.plugin.Mojo {
        val config = extractPluginConfiguration(artifactId, File(pomPath))
        return lookupMojo(groupId, artifactId, version, goal, config)
    }

    @Test
    fun `generateSQL goal wires from descriptor and writes SQL`() {
        val mojo = lookupFromPom("generateSQL", "src/test/resources/unit/generate-basic/pom.xml") as GenerateMojo
        assertNotNull(mojo)

        // outputFormat=RIVER comes from the POM; verify it was injected.
        assertEquals("RIVER", mojo.outputFormat)

        val out = Files.createTempDirectory("harness-gen-out-").toFile()
        mojo.rootOutputPath = out
        try {
            mojo.execute()
            val sqlFile = File(out, "widget.sql")
            assertTrue(sqlFile.exists(), "widget.sql should have been generated")
            assertTrue(sqlFile.readText().contains("INSERT INTO harness_test.widget"))
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun `validate goal wires from descriptor and writes nothing`() {
        val mojo = lookupFromPom("validate", "src/test/resources/unit/validate-basic/pom.xml") as ValidateMojo
        assertNotNull(mojo)

        // strict=false comes from the POM; verify it overrode the true default.
        assertFalse(mojo.strict)

        val out = Files.createTempDirectory("harness-val-out-").toFile()
        mojo.rootOutputPath = out
        try {
            mojo.execute()
            assertEquals(0, out.listFiles()?.size ?: 0)
        } finally {
            out.deleteRecursively()
        }
    }
}
