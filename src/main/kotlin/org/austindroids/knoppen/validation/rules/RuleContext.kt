package org.austindroids.knoppen.validation.rules


import org.austindroids.knoppen.validation.ValidationError
import org.austindroids.knoppen.validation.YamlLoader
import tools.jackson.databind.JsonNode


/**
 * Shared context threaded through all validators.
 * Accumulates errors so every rule can append without short-circuiting.
 */
class RuleContext(
    val root: JsonNode,
    val lineIndex: YamlLoader.LineIndex
) {
    private val _errors = mutableListOf<ValidationError>()
    val errors: List<ValidationError> get() = _errors.toList()

    fun error(path: String, message: String) {
        _errors.add(
            ValidationError(
                path = path,
                message = message,
                line = lineIndex.lineFor(path)
            )
        )
    }

    fun warning(path: String, message: String) {
        _errors.add(
            ValidationError(
                path = path,
                message = message,
                line = lineIndex.lineFor(path),
                severity = ValidationError.Severity.WARNING
            )
        )
    }

    /** Convenience: navigate to a path and return the node, or null */
    fun nodeAt(path: String): JsonNode? {
        // Convert "/tables/0/columns/1" to Jackson pointer "/tables/0/columns/1"
        return root.at(path).takeIf { !it.isMissingNode }
    }
}
