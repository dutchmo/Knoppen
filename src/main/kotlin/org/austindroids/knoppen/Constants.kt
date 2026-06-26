package org.austindroids.knoppen

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset


object ResourceConstants {

    const val META_JSON_SCHEMA = "schema/json-schema.json"

    // Application configs
    const val APP_PROPERTIES = "application.properties"
    const val LOG4J2_XML = "log4j2.xml"

    private val buildProps: java.util.Properties by lazy {
        java.util.Properties().also { props ->
            try {
                javaClass.classLoader
                    .getResourceAsStream("version.properties")
                    ?.use { props.load(it) }
            } catch (_: Exception) {}
        }
    }

    val APP_VERSION: String get() = buildProps.getProperty("version", "Unknown")
    val APP_NAME:    String get() = buildProps.getProperty("appName",  "Knoppen")
}



interface ResourceLoader {
    fun resourceStream(path: String): InputStream
}

// Extension functions for convenience – they all build on the stream contract
fun ResourceLoader.resourceText(path: String, charset: Charset = Charsets.UTF_8): String =
    resourceStream(path).use { it.bufferedReader(charset).readText() }

fun ResourceLoader.resourceLines(path: String, charset: Charset = Charsets.UTF_8): List<String> =
    resourceStream(path).use { it.bufferedReader(charset).readLines() }

fun ResourceLoader.resourceBytes(path: String): ByteArray =
    resourceStream(path).use { it.readBytes() }

// --- Default implementation using classpath ---
class ClasspathResourceLoader(private val classLoader: ClassLoader) : ResourceLoader {

    constructor(sourceClass: Class<*>) : this(sourceClass.classLoader)

    override fun resourceStream(path: String): InputStream =
        classLoader.getResourceAsStream(path)
            ?: throw ResourceNotFoundException(path, classLoader)
    companion object {
        fun from(sampleClass: Class<*>) = ClasspathResourceLoader(sampleClass)
    }


}

class ResourceNotFoundException(path: String, classLoader: ClassLoader)
    : RuntimeException("Resource not found: $path (classLoader=$classLoader)")


