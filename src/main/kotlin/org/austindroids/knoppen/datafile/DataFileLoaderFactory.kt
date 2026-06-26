package org.austindroids.knoppen.datafile

/**
 * Supported data file formats.
 */
enum class DataFileType(val extensions: List<String>) {
    YAML(listOf("yaml", "yml")),
    JSON(listOf("json")),
    CSV (listOf("csv"));

    companion object {
        fun fromExtension(ext: String): DataFileType =
            entries.firstOrNull { ext.lowercase() in it.extensions }
                ?: throw IllegalArgumentException(
                    "Unsupported data file extension '.$ext'. " +
                    "Allowed: ${entries.flatMap { it.extensions }.joinToString(", ") { ".$it" }}"
                )
    }
}

/**
 * Returns the appropriate [DataFileLoader] for a given file extension.
 */
object DataFileLoaderFactory {
    fun forExtension(ext: String): DataFileLoader = when (DataFileType.fromExtension(ext)) {
        DataFileType.YAML -> YamlDataFileLoader()
        DataFileType.JSON -> JsonDataFileLoader()
        DataFileType.CSV  -> CsvDataFileLoader()
    }
}
