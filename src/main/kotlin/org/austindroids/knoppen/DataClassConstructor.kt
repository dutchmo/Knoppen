package org.austindroids.knoppen

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.TypeDescription
import org.yaml.snakeyaml.constructor.Constructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import kotlin.reflect.KClass

/**
 * Custom constructor that tracks line numbers for both objects and properties
 * when parsing YAML to Kotlin data classes.
 */
@Deprecated("Use DataClassConstructor instead")
class DataClassConstructorDeprecated<T : Any>(
    rootClass: KClass<T>
) : Constructor(TypeDescription(rootClass.java), LoaderOptions()) {

    private val lineNumberTracker = LineNumberTracker()

    override fun constructObject(node: Node): Any {
        val result = super.constructObject(node)
        lineNumberTracker.trackObject(node, result)
        return result
    }

    fun getLineNumberInfo(): LineNumberTracker = lineNumberTracker


    /**
     * Tracks line numbers by intercepting the construction process
     */
    class LineNumberTracker {

        private val objectLineNumbers = mutableMapOf<Any, Int>()
        private val propertyLineNumbers = mutableMapOf<Any, MutableMap<String, Int>>()

        fun trackObject(node: Node, obj: Any) {
            if (node.startMark != null) {
                objectLineNumbers[obj] = node.startMark.line + 1
            }

            // For mapping nodes, track properties
            if (node is MappingNode) {
                val propertyLines = mutableMapOf<String, Int>()
                node.value.forEach { tuple ->
                    val keyNode = tuple.keyNode
                    val valueNode = tuple.valueNode

                    if (keyNode is ScalarNode && valueNode.startMark != null) {
                        propertyLines[keyNode.value] = valueNode.startMark.line + 1
                    }
                }

                if (propertyLines.isNotEmpty()) {
                    propertyLineNumbers[obj] = propertyLines
                }
            }
        }

        fun getObjectLineNumber(obj: Any): Int? = objectLineNumbers[obj]
        fun getPropertyLineNumber(obj: Any, propertyName: String): Int? = propertyLineNumbers[obj]?.get(propertyName)
        fun getAllPropertyLineNumbers(obj: Any): Map<String, Int>? = propertyLineNumbers[obj]
    }

}

