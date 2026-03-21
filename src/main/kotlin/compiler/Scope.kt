package org.example.compiler

import org.antlr.v4.runtime.ParserRuleContext

/**
 * Immutable lexical scope stack for variable bindings.
 */
internal data class Scope(
    val frames: List<Map<String, VariableBinding>>,
) {
    /**
     * Creates a nested lexical scope.
     */
    fun pushScope(): Scope = Scope(frames + mapOf())

    /**
     * Declares a variable in the current lexical scope.
     */
    fun declare(
        binding: VariableBinding,
        context: ParserRuleContext,
    ): Scope {
        val current = frames.last()
        if (current.containsKey(binding.name)) {
            throw IllegalArgumentException(
                "Variable '${binding.name}' is already declared in this scope " +
                    "(line ${context.start?.line ?: 0}, column ${(context.start?.charPositionInLine ?: 0) + 1})",
            )
        }

        val updatedCurrent = current + (binding.name to binding)
        return Scope(frames.dropLast(1) + updatedCurrent)
    }

    /**
     * Resolves a variable from the nearest enclosing lexical scope.
     */
    fun resolve(name: String): VariableBinding? = frames.asReversed().firstNotNullOfOrNull { it[name] }

    companion object {
        /**
         * Creates the root scope for a function body.
         */
        fun root(): Scope = Scope(listOf(mapOf()))
    }
}
