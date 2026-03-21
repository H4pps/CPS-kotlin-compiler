package org.example.compiler

internal data class CompilationContext(
    val className: String,
    val functionSignatures: Map<String, FunctionSignature>,
)

internal data class FunctionSignature(
    val name: String,
    val parameters: List<ParameterSignature>,
    val returnType: MiniType,
)

internal data class ParameterSignature(
    val name: String,
    val type: MiniType,
)

internal data class VariableBinding(
    val name: String,
    val type: MiniType,
    val refName: String,
)

internal data class TypedValue(
    val expression: String,
    val type: MiniType,
)

internal enum class MiniType(
    val displayName: String,
    val javaType: String,
) {
    INT("Int", "Integer"),
    STRING("String", "String"),
    BOOLEAN("Boolean", "Boolean"),
    UNIT("Unit", "Void"),
}
