package org.austindroids.knoppen.sqlgen

import org.austindroids.knoppen.schema.DatabaseSchema
import org.austindroids.knoppen.schema.TableSchema

/**
 * Builds a dependency DAG from the FK references in a [DatabaseSchema] and
 * produces a topological ordering suitable for data loading and SQL generation.
 *
 * A table A *depends on* table B when any column of A has a foreign key pointing
 * to B. In the output of [sort], B appears before A.
 *
 * When multiple independent tables could be processed next, declaration order
 * in the schema is preserved (stable sort).
 *
 * Cycles are detected via Kahn's algorithm — if the graph cannot be fully
 * linearised, [sort] throws [CyclicDependencyException].
 */
class TableDependencyResolver(private val schema: DatabaseSchema) {

    class CyclicDependencyException(message: String) : Exception(message)

    /**
     * Returns all tables in dependency order.
     * @throws CyclicDependencyException if a circular FK reference is found
     */
    fun sort(): List<TableSchema> {
        val tableMap   = schema.tables.associateBy { it.tableName }
        val declOrder  = schema.tables.mapIndexed { i, t -> t.tableName to i }.toMap()

        // deps[table] = set of tables it directly depends on (only within this schema)
        val deps: Map<String, Set<String>> = schema.tables.associate { table ->
            table.tableName to table.columns
                .mapNotNull { col -> col.foreignKey?.table }
                .filter    { it in tableMap && it != table.tableName }
                .toSet()
        }

        // Build in-degree map and reverse adjacency list
        val inDegree   = schema.tables.associate { it.tableName to 0 }.toMutableMap()
        val dependents = schema.tables.associate { it.tableName to mutableListOf<String>() }.toMutableMap()

        for ((tableName, tableDeps) in deps) {
            for (dep in tableDeps) {
                inDegree[tableName] = (inDegree[tableName] ?: 0) + 1
                dependents.getOrPut(dep) { mutableListOf() }.add(tableName)
            }
        }

        // Kahn's algorithm — use a priority queue to preserve declaration order for ties
        val queue = java.util.PriorityQueue<String>(compareBy { declOrder[it] ?: Int.MAX_VALUE })
        inDegree.filter { (_, deg) -> deg == 0 }.keys.forEach { queue.add(it) }

        val sorted = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.poll()
            sorted.add(current)
            for (dependent in dependents[current] ?: emptyList()) {
                val newDeg = (inDegree[dependent] ?: 1) - 1
                inDegree[dependent] = newDeg
                if (newDeg == 0) queue.add(dependent)
            }
        }

        if (sorted.size != tableMap.size) {
            val cycleNodes = tableMap.keys - sorted.toSet()
            throw CyclicDependencyException(
                "Cycle detected between tables: ${cycleNodes.sorted().joinToString(", ")}. " +
                "Add an explicit depends_on or restructure the foreign keys."
            )
        }

        return sorted.mapNotNull { tableMap[it] }
    }
}
