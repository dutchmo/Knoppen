package org.austindroids.knoppen.validation


import org.austindroids.knoppen.ClasspathResourceLoader
import org.austindroids.knoppen.ResourceConstants
import org.austindroids.knoppen.ResourceLoader
import org.austindroids.knoppen.resourceText
import org.austindroids.knoppen.validation.rules.RuleContext
import org.austindroids.knoppen.validation.rules.SemanticValidator
import org.austindroids.knoppen.validation.rules.StructuralValidator
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper


/**
 * Main entry point.
 *
 * Usage:
 * ```
 * val result = SchemaValidator.validate(
 *     yamlContent = File("schema.yaml").readText(),
 *     jsonSchemaContent = File("schema.json").readText()
 * )
 * if (result.hasErrors()) {
 *     result.errors.forEach { println(it) }
 * }
 * ```
 */
object SchemaValidator {

    private val log    = LoggerFactory.getLogger(SchemaValidator::class.java)
    private val loader = ClasspathResourceLoader(ResourceLoader::class.java)

    data class ValidationResult(
        val errors: List<ValidationError>
    ) {
        val hasErrors: Boolean   get() = errors.any { it.severity == ValidationError.Severity.ERROR }
        val hasWarnings: Boolean get() = errors.any { it.severity == ValidationError.Severity.WARNING }

        fun prettyPrint(): String = buildString {
            if (errors.isEmpty()) {
                appendLine("✅ Validation passed with no issues.")
                return@buildString
            }
            val (errs, warns) = errors.partition { it.severity == ValidationError.Severity.ERROR }
            if (errs.isNotEmpty()) {
                appendLine("❌ ${errs.size} error(s):")
                errs.forEach { appendLine("   $it") }
            }
            if (warns.isNotEmpty()) {
                appendLine("⚠\uFE0F  ${warns.size} warning(s):")
                warns.forEach { appendLine("   $it") }
            }
        }
    }

    fun validate(yamlContent: String): ValidationResult {
        log.debug("Validating schema ({} chars)", yamlContent.length)
        val metaSchemaJson = loader.resourceText(ResourceConstants.META_JSON_SCHEMA)
        // ── Step 1: Parse YAML + capture line numbers ─────────────────────────
        val loader = YamlLoader()
        val (root, lineIndex) = loader.load(yamlContent)

        // ── Step 2: Convert to JSON node tree ─────────────────────────────────
        // networknt works on Jackson JsonNode so we re-serialize via JSON
        // to ensure the node tree is in a form the schema validator expects
        val jsonMapper = ObjectMapper()
        val jsonString = jsonMapper.writeValueAsString(root)
        val jsonNode = jsonMapper.readTree(jsonString)

        val context = RuleContext(jsonNode, lineIndex)

        // ── Step 3: Structural validation (JSON Schema) ───────────────────────
        StructuralValidator(metaSchemaJson).validate(context)

        // ── Step 4: Semantic validation (cross-field business rules) ──────────
        // Only run if structural validation passed — semantic rules assume
        // required fields are present and types are correct
        if (!context.errors.any { it.severity == ValidationError.Severity.ERROR }) {
            SemanticValidator().validate(context)
        }

        val errorCount   = context.errors.count { it.severity == ValidationError.Severity.ERROR }
        val warningCount = context.errors.count { it.severity == ValidationError.Severity.WARNING }
        log.debug("Schema validation complete: {} error(s), {} warning(s)", errorCount, warningCount)

        return ValidationResult(context.errors)
    }
}

