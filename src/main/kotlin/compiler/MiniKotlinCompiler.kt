package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser
import org.antlr.v4.runtime.ParserRuleContext

/**
 * Compiles MiniKotlin parse trees into Java source code that uses continuation-passing style.
 */
class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {
    /**
     * Mutable compilation context used while visiting the parse tree.
     */
    private lateinit var compilationContext: CompilationContext

    /**
     * Compiles a MiniKotlin program into a Java class.
     */
    fun compile(
        program: MiniKotlinParser.ProgramContext,
        className: String = "MiniProgram",
    ): String {
        val signatures = collectFunctionSignatures(program)
        compilationContext = CompilationContext(className = className, functionSignatures = signatures)
        return visitProgram(program)
    }

    /**
     * Emits the Java class for the complete program.
     */
    override fun visitProgram(ctx: MiniKotlinParser.ProgramContext): String {
        val functions = ctx.functionDeclaration().joinToString("\n\n") { visitFunctionDeclaration(it) }
        val mainBridge = emitMainBridgeIfPresent()

        val classMembers =
            listOf(mainBridge, functions, emitRefHelperClass())
                .filter { it.isNotBlank() }
                .joinToString("\n\n")

        val rawJava =
            """
            public class ${compilationContext.className} {
            ${indent(classMembers)}
            }
            """.trimIndent()

        return formatJava(rawJava)
    }

    /**
     * Emits one CPS Java method for a MiniKotlin function declaration.
     */
    override fun visitFunctionDeclaration(ctx: MiniKotlinParser.FunctionDeclarationContext): String {
        val functionName = ctx.IDENTIFIER().text
        val signature =
            compilationContext.functionSignatures[functionName]
                ?: compilerError("Internal error: missing signature for function '$functionName'", ctx)
        val nameGenerator = NameGenerator()

        var scope = Scope.root()
        val parameterInitializers = mutableListOf<String>()
        signature.parameters.forEach { parameter ->
            val refName = nameGenerator.nextRefName(parameter.name)
            val binding = VariableBinding(name = parameter.name, type = parameter.type, refName = refName)
            scope = scope.declare(binding, ctx)
            parameterInitializers += "Ref<${parameter.type.javaType}> $refName = new Ref<>(${parameter.name});"
        }

        val body =
            emitBlock(
                block = ctx.block(),
                scope = scope,
                signature = signature,
                nameGenerator = nameGenerator,
                onComplete = {
                    if (signature.returnType == MiniType.UNIT) {
                        "__continuation.accept(null);\nreturn;"
                    } else {
                        "throw new IllegalStateException(\"Function '${signature.name}' completed without a return value.\");"
                    }
                },
            )

        val methodParameters =
            buildList {
                addAll(signature.parameters.map { "${it.type.javaType} ${it.name}" })
                add("Continuation<${signature.returnType.javaType}> __continuation")
            }.joinToString(", ")

        val prelude =
            if (parameterInitializers.isEmpty()) {
                body
            } else {
                parameterInitializers.joinToString("\n") + "\n" + body
            }

        return """
            public static void ${signature.name}($methodParameters) {
            ${indent(prelude)}
            }
            """.trimIndent()
    }

    /**
     * Collects and validates all function signatures in declaration order.
     */
    private fun collectFunctionSignatures(program: MiniKotlinParser.ProgramContext): LinkedHashMap<String, FunctionSignature> {
        val signatures = linkedMapOf<String, FunctionSignature>()

        program.functionDeclaration().forEach { functionDeclaration ->
            val name = functionDeclaration.IDENTIFIER().text
            if (signatures.containsKey(name)) {
                compilerError("Function '$name' is already declared", functionDeclaration)
            }

            val parameters =
                functionDeclaration
                    .parameterList()
                    ?.parameter()
                    ?.map { parameter ->
                        ParameterSignature(
                            name = parameter.IDENTIFIER().text,
                            type = parseType(parameter.type(), parameter),
                        )
                    }
                    ?: emptyList()

            val duplicateParameter = parameters.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
            if (duplicateParameter != null) {
                compilerError(
                    "Function '$name' has duplicate parameter '${duplicateParameter.key}'",
                    functionDeclaration,
                )
            }

            val returnType = parseType(functionDeclaration.type(), functionDeclaration)
            signatures[name] = FunctionSignature(name = name, parameters = parameters, returnType = returnType)
        }

        return signatures
    }

    /**
     * Emits a Java main bridge that starts MiniKotlin `main(): Unit` in CPS form.
     */
    private fun emitMainBridgeIfPresent(): String {
        val main = compilationContext.functionSignatures["main"] ?: return ""
        if (main.parameters.isNotEmpty() || main.returnType != MiniType.UNIT) {
            return ""
        }

        val continuationArg = "__mainDone"
        return """
            public static void main(String[] args) {
              main(($continuationArg) -> {
              });
            }
            """.trimIndent()
    }

    /**
     * Emits the generic mutable reference helper used for mutable variables captured by lambdas.
     */
    private fun emitRefHelperClass(): String =
        """
        private static final class Ref<T> {
          private T value;

          private Ref(T value) {
            this.value = value;
          }
        }
        """.trimIndent()

    /**
     * Emits a block with lexical scoping and continuation-based completion.
     */
    private fun emitBlock(
        block: MiniKotlinParser.BlockContext,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        onComplete: () -> String,
    ): String {
        val scoped = scope.pushScope()
        return emitStatements(block.statement(), 0, scoped, signature, nameGenerator) { onComplete() }
    }

    /**
     * Emits a sequence of statements.
     */
    private fun emitStatements(
        statements: List<MiniKotlinParser.StatementContext>,
        index: Int,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        onComplete: () -> String,
    ): String {
        if (index >= statements.size) {
            return onComplete()
        }

        return emitStatement(statements[index], scope, signature, nameGenerator) { nextScope ->
            emitStatements(statements, index + 1, nextScope, signature, nameGenerator, onComplete)
        }
    }

    /**
     * Emits one statement and links it to the rest of the block.
     */
    private fun emitStatement(
        statement: MiniKotlinParser.StatementContext,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        onComplete: (Scope) -> String,
    ): String {
        statement.variableDeclaration()?.let {
            return emitVariableDeclaration(it, scope, signature, nameGenerator, onComplete)
        }
        statement.variableAssignment()?.let {
            return emitVariableAssignment(it, scope, signature, nameGenerator, onComplete)
        }
        statement.ifStatement()?.let {
            return emitIfStatement(it, scope, signature, nameGenerator, onComplete)
        }
        statement.whileStatement()?.let {
            return emitWhileStatement(it, scope, signature, nameGenerator, onComplete)
        }
        statement.returnStatement()?.let {
            return emitReturnStatement(it, scope, signature, nameGenerator)
        }
        statement.expression()?.let {
            return emitExpression(it, scope, signature, nameGenerator) {
                onComplete(scope)
            }
        }

        compilerError("Unsupported statement", statement)
    }

    /**
     * Emits a mutable variable declaration.
     */
    private fun emitVariableDeclaration(
        declaration: MiniKotlinParser.VariableDeclarationContext,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        onComplete: (Scope) -> String,
    ): String {
        val variableName = declaration.IDENTIFIER().text
        val declaredType = parseType(declaration.type(), declaration)

        return emitExpression(declaration.expression(), scope, signature, nameGenerator) { initializerValue ->
            ensureAssignable(
                expected = declaredType,
                actual = initializerValue.type,
                message = "Variable '$variableName' expects ${declaredType.displayName} but got ${initializerValue.type.displayName}",
                context = declaration,
            )

            val binding =
                VariableBinding(
                    name = variableName,
                    type = declaredType,
                    refName = nameGenerator.nextRefName(variableName),
                )
            val updatedScope = scope.declare(binding, declaration)
            val continuation = onComplete(updatedScope)

            """
            Ref<${declaredType.javaType}> ${binding.refName} = new Ref<>(${adaptValue(initializerValue, declaredType)});
            $continuation
            """.trimIndent()
        }
    }

    /**
     * Emits assignment to an existing mutable variable.
     */
    private fun emitVariableAssignment(
        assignment: MiniKotlinParser.VariableAssignmentContext,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        onComplete: (Scope) -> String,
    ): String {
        val variableName = assignment.IDENTIFIER().text
        val binding =
            scope.resolve(variableName)
                ?: compilerError("Unknown variable '$variableName'", assignment)

        return emitExpression(assignment.expression(), scope, signature, nameGenerator) { value ->
            ensureAssignable(
                expected = binding.type,
                actual = value.type,
                message = "Assignment to '$variableName' expects ${binding.type.displayName} but got ${value.type.displayName}",
                context = assignment,
            )

            val continuation = onComplete(scope)
            """
            ${binding.refName}.value = ${adaptValue(value, binding.type)};
            $continuation
            """.trimIndent()
        }
    }

    /**
     * Emits an `if` statement with optional `else` branch.
     */
    private fun emitIfStatement(
        ifStatement: MiniKotlinParser.IfStatementContext,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        onComplete: (Scope) -> String,
    ): String {
        val continuation = onComplete(scope)

        return emitExpression(ifStatement.expression(), scope, signature, nameGenerator) { condition ->
            ensureAssignable(
                expected = MiniType.BOOLEAN,
                actual = condition.type,
                message = "If condition must be Boolean but got ${condition.type.displayName}",
                context = ifStatement,
            )

            val thenBlock = emitBlock(ifStatement.block(0), scope, signature, nameGenerator) { continuation }
            val elseBlock =
                if (ifStatement.block().size > 1) {
                    emitBlock(ifStatement.block(1), scope, signature, nameGenerator) { continuation }
                } else {
                    continuation
                }

            """
            if (${condition.expression}) {
            ${indent(thenBlock)}
            } else {
            ${indent(elseBlock)}
            }
            """.trimIndent()
        }
    }

    /**
     * Emits a `while` loop as a self-recursive continuation.
     */
    private fun emitWhileStatement(
        whileStatement: MiniKotlinParser.WhileStatementContext,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        onComplete: (Scope) -> String,
    ): String {
        val continuation = onComplete(scope)
        val loopRef = nameGenerator.nextTempName("loop")
        val loopArg = nameGenerator.nextLambdaName("loopArg")

        val body =
            emitBlock(whileStatement.block(), scope, signature, nameGenerator) {
                "$loopRef[0].accept(null);\nreturn;"
            }

        val condition =
            emitExpression(whileStatement.expression(), scope, signature, nameGenerator) { conditionValue ->
                ensureAssignable(
                    expected = MiniType.BOOLEAN,
                    actual = conditionValue.type,
                    message = "While condition must be Boolean but got ${conditionValue.type.displayName}",
                    context = whileStatement,
                )

                """
                if (${conditionValue.expression}) {
                ${indent(body)}
                } else {
                ${indent(continuation)}
                }
                """.trimIndent()
            }

        return """
            final Continuation<Void>[] $loopRef = new Continuation[1];
            $loopRef[0] = ($loopArg) -> {
            ${indent(condition)}
            };
            $loopRef[0].accept(null);
            """.trimIndent()
    }

    /**
     * Emits a function return statement.
     */
    private fun emitReturnStatement(
        returnStatement: MiniKotlinParser.ReturnStatementContext,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
    ): String {
        val expression = returnStatement.expression()

        if (expression == null) {
            if (signature.returnType != MiniType.UNIT) {
                compilerError(
                    "Function '${signature.name}' must return ${signature.returnType.displayName}",
                    returnStatement,
                )
            }
            return "__continuation.accept(null);\nreturn;"
        }

        return emitExpression(expression, scope, signature, nameGenerator) { value ->
            if (signature.returnType == MiniType.UNIT) {
                ensureAssignable(
                    expected = MiniType.UNIT,
                    actual = value.type,
                    message = "Function '${signature.name}' has return type Unit and cannot return ${value.type.displayName}",
                    context = returnStatement,
                )
                "__continuation.accept(null);\nreturn;"
            } else {
                ensureAssignable(
                    expected = signature.returnType,
                    actual = value.type,
                    message =
                        "Function '${signature.name}' has return type " +
                            "${signature.returnType.displayName} but returned ${value.type.displayName}",
                    context = returnStatement,
                )
                "__continuation.accept(${adaptValue(value, signature.returnType)});\nreturn;"
            }
        }
    }

    /**
     * Emits an expression in CPS form and passes its value to the continuation.
     */
    private fun emitExpression(
        expression: MiniKotlinParser.ExpressionContext,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        continuation: (TypedValue) -> String,
    ): String =
        when (expression) {
            is MiniKotlinParser.PrimaryExprContext ->
                emitPrimaryExpression(
                    expression.primary(),
                    scope,
                    signature,
                    nameGenerator,
                    continuation,
                )

            is MiniKotlinParser.NotExprContext ->
                emitExpression(
                    expression.expression(),
                    scope,
                    signature,
                    nameGenerator,
                ) { value ->
                    ensureAssignable(
                        expected = MiniType.BOOLEAN,
                        actual = value.type,
                        message = "Logical NOT requires Boolean but got ${value.type.displayName}",
                        context = expression,
                    )
                    continuation(TypedValue(expression = "(!${value.expression})", type = MiniType.BOOLEAN))
                }

            is MiniKotlinParser.AddSubExprContext ->
                emitBinaryExpression(
                    left = expression.expression(0),
                    right = expression.expression(1),
                    scope = scope,
                    signature = signature,
                    nameGenerator = nameGenerator,
                    expressionContext = expression,
                    continuation = continuation,
                ) { left, right ->
                    val operator = expression.getChild(1).text
                    when (operator) {
                        "+" -> {
                            when {
                                left.type == MiniType.INT && right.type == MiniType.INT -> {
                                    TypedValue("(${left.expression} + ${right.expression})", MiniType.INT)
                                }

                                left.type == MiniType.STRING || right.type == MiniType.STRING -> {
                                    ensureNoUnitForStringConcat(left, expression)
                                    ensureNoUnitForStringConcat(right, expression)
                                    TypedValue(
                                        "(String.valueOf(${left.expression}) + String.valueOf(${right.expression}))",
                                        MiniType.STRING,
                                    )
                                }

                                else ->
                                    compilerError(
                                        "Operator '+' requires Int + Int or String concatenation, but got ${left.type.displayName} and ${right.type.displayName}",
                                        expression,
                                    )
                            }
                        }

                        "-" -> {
                            ensureIntBinary(left, right, "-", expression)
                            TypedValue("(${left.expression} - ${right.expression})", MiniType.INT)
                        }

                        else -> compilerError("Unsupported additive operator '$operator'", expression)
                    }
                }

            is MiniKotlinParser.MulDivExprContext ->
                emitBinaryExpression(
                    left = expression.expression(0),
                    right = expression.expression(1),
                    scope = scope,
                    signature = signature,
                    nameGenerator = nameGenerator,
                    expressionContext = expression,
                    continuation = continuation,
                ) { left, right ->
                    val operator = expression.getChild(1).text
                    ensureIntBinary(left, right, operator, expression)
                    TypedValue("(${left.expression} $operator ${right.expression})", MiniType.INT)
                }

            is MiniKotlinParser.ComparisonExprContext ->
                emitBinaryExpression(
                    left = expression.expression(0),
                    right = expression.expression(1),
                    scope = scope,
                    signature = signature,
                    nameGenerator = nameGenerator,
                    expressionContext = expression,
                    continuation = continuation,
                ) { left, right ->
                    val operator = expression.getChild(1).text
                    ensureIntBinary(left, right, operator, expression)
                    TypedValue("(${left.expression} $operator ${right.expression})", MiniType.BOOLEAN)
                }

            is MiniKotlinParser.EqualityExprContext ->
                emitBinaryExpression(
                    left = expression.expression(0),
                    right = expression.expression(1),
                    scope = scope,
                    signature = signature,
                    nameGenerator = nameGenerator,
                    expressionContext = expression,
                    continuation = continuation,
                ) { left, right ->
                    val operator = expression.getChild(1).text
                    val base = "java.util.Objects.equals(${left.expression}, ${right.expression})"
                    val rendered = if (operator == "==") base else "(!$base)"
                    TypedValue(rendered, MiniType.BOOLEAN)
                }

            is MiniKotlinParser.AndExprContext ->
                emitLogicalAndExpression(
                    expression,
                    scope,
                    signature,
                    nameGenerator,
                    continuation,
                )

            is MiniKotlinParser.OrExprContext ->
                emitLogicalOrExpression(
                    expression,
                    scope,
                    signature,
                    nameGenerator,
                    continuation,
                )

            is MiniKotlinParser.FunctionCallExprContext ->
                emitFunctionCallExpression(
                    expression,
                    scope,
                    signature,
                    nameGenerator,
                    continuation,
                )

            else -> compilerError("Unsupported expression", expression)
        }

    /**
     * Emits a primary expression.
     */
    private fun emitPrimaryExpression(
        primary: MiniKotlinParser.PrimaryContext,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        continuation: (TypedValue) -> String,
    ): String =
        when (primary) {
            is MiniKotlinParser.IntLiteralContext -> {
                continuation(TypedValue(expression = primary.INTEGER_LITERAL().text, type = MiniType.INT))
            }

            is MiniKotlinParser.StringLiteralContext -> {
                continuation(TypedValue(expression = primary.STRING_LITERAL().text, type = MiniType.STRING))
            }

            is MiniKotlinParser.BoolLiteralContext -> {
                continuation(TypedValue(expression = primary.BOOLEAN_LITERAL().text, type = MiniType.BOOLEAN))
            }

            is MiniKotlinParser.IdentifierExprContext -> {
                val name = primary.IDENTIFIER().text
                val binding = scope.resolve(name) ?: compilerError("Unknown variable '$name'", primary)
                continuation(TypedValue(expression = "${binding.refName}.value", type = binding.type))
            }

            is MiniKotlinParser.ParenExprContext ->
                emitExpression(
                    expression = primary.expression(),
                    scope = scope,
                    signature = signature,
                    nameGenerator = nameGenerator,
                ) { value ->
                    continuation(TypedValue(expression = "(${value.expression})", type = value.type))
                }

            else -> compilerError("Unsupported primary expression", primary)
        }

    /**
     * Emits a binary expression with left-to-right evaluation.
     */
    private fun emitBinaryExpression(
        left: MiniKotlinParser.ExpressionContext,
        right: MiniKotlinParser.ExpressionContext,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        expressionContext: ParserRuleContext,
        continuation: (TypedValue) -> String,
        combine: (TypedValue, TypedValue) -> TypedValue,
    ): String =
        emitExpression(left, scope, signature, nameGenerator) { leftValue ->
            emitExpression(right, scope, signature, nameGenerator) { rightValue ->
                val combined = combine(leftValue, rightValue)
                continuationWithValidation(combined, expressionContext, combine = continuation)
            }
        }

    /**
     * Emits a logical AND expression with short-circuit behavior.
     */
    private fun emitLogicalAndExpression(
        expression: MiniKotlinParser.AndExprContext,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        continuation: (TypedValue) -> String,
    ): String =
        emitExpression(expression.expression(0), scope, signature, nameGenerator) { left ->
            ensureAssignable(
                expected = MiniType.BOOLEAN,
                actual = left.type,
                message = "Operator '&&' requires Boolean operands but left side is ${left.type.displayName}",
                context = expression,
            )

            val resultRef = nameGenerator.nextTempName("andRef")
            val rightBranch =
                emitExpression(expression.expression(1), scope, signature, nameGenerator) { right ->
                    ensureAssignable(
                        expected = MiniType.BOOLEAN,
                        actual = right.type,
                        message = "Operator '&&' requires Boolean operands but right side is ${right.type.displayName}",
                        context = expression,
                    )
                    "$resultRef.value = ${right.expression};"
                }
            val continuationCode = continuation(TypedValue("$resultRef.value", MiniType.BOOLEAN))

            """
            Ref<Boolean> $resultRef = new Ref<>(false);
            if (!${left.expression}) {
              $resultRef.value = false;
            } else {
            ${indent(rightBranch)}
            }
            $continuationCode
            """.trimIndent()
        }

    /**
     * Emits a logical OR expression with short-circuit behavior.
     */
    private fun emitLogicalOrExpression(
        expression: MiniKotlinParser.OrExprContext,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        continuation: (TypedValue) -> String,
    ): String =
        emitExpression(expression.expression(0), scope, signature, nameGenerator) { left ->
            ensureAssignable(
                expected = MiniType.BOOLEAN,
                actual = left.type,
                message = "Operator '||' requires Boolean operands but left side is ${left.type.displayName}",
                context = expression,
            )

            val resultRef = nameGenerator.nextTempName("orRef")
            val rightBranch =
                emitExpression(expression.expression(1), scope, signature, nameGenerator) { right ->
                    ensureAssignable(
                        expected = MiniType.BOOLEAN,
                        actual = right.type,
                        message = "Operator '||' requires Boolean operands but right side is ${right.type.displayName}",
                        context = expression,
                    )
                    "$resultRef.value = ${right.expression};"
                }
            val continuationCode = continuation(TypedValue("$resultRef.value", MiniType.BOOLEAN))

            """
            Ref<Boolean> $resultRef = new Ref<>(false);
            if (${left.expression}) {
              $resultRef.value = true;
            } else {
            ${indent(rightBranch)}
            }
            $continuationCode
            """.trimIndent()
        }

    /**
     * Emits function-call expressions including builtin `println` handling.
     */
    private fun emitFunctionCallExpression(
        expression: MiniKotlinParser.FunctionCallExprContext,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        continuation: (TypedValue) -> String,
    ): String {
        val functionName = expression.IDENTIFIER().text
        val arguments = expression.argumentList().expression()

        return emitArguments(arguments, 0, scope, signature, nameGenerator, emptyList()) { evaluatedArguments ->
            if (functionName == "println") {
                if (evaluatedArguments.size != 1) {
                    compilerError("Function 'println' expects exactly 1 argument", expression)
                }
                val argument = evaluatedArguments.first()
                val continuationArg = nameGenerator.nextLambdaName("printlnDone")
                val continuationCode = continuation(TypedValue(expression = "null", type = MiniType.UNIT))
                val renderedArgument = if (argument.type == MiniType.UNIT) "null" else argument.expression

                """
                Prelude.println($renderedArgument, ($continuationArg) -> {
                ${indent(continuationCode)}
                });
                """.trimIndent()
            } else {
                val callee =
                    compilationContext.functionSignatures[functionName]
                        ?: compilerError("Unknown function '$functionName'", expression)

                if (callee.parameters.size != evaluatedArguments.size) {
                    compilerError(
                        "Function '$functionName' expects ${callee.parameters.size} arguments but got ${evaluatedArguments.size}",
                        expression,
                    )
                }

                val renderedArguments =
                    callee.parameters.zip(evaluatedArguments).mapIndexed { index, (parameter, argument) ->
                        ensureAssignable(
                            expected = parameter.type,
                            actual = argument.type,
                            message =
                                "Argument ${index + 1} for '$functionName' expects " +
                                    "${parameter.type.displayName} but got ${argument.type.displayName}",
                            context = expression,
                        )
                        adaptValue(argument, parameter.type)
                    }

                val continuationArg = nameGenerator.nextLambdaName("${functionName}Result")
                val continuationCode =
                    if (callee.returnType == MiniType.UNIT) {
                        continuation(TypedValue(expression = "null", type = MiniType.UNIT))
                    } else {
                        continuation(TypedValue(expression = continuationArg, type = callee.returnType))
                    }

                val callArguments =
                    renderedArguments +
                        "($continuationArg) -> {\n${indent(continuationCode)}\n}"
                "${callee.name}(${callArguments.joinToString(", ")});"
            }
        }
    }

    /**
     * Emits argument expressions from left to right.
     */
    private fun emitArguments(
        arguments: List<MiniKotlinParser.ExpressionContext>,
        index: Int,
        scope: Scope,
        signature: FunctionSignature,
        nameGenerator: NameGenerator,
        collected: List<TypedValue>,
        continuation: (List<TypedValue>) -> String,
    ): String {
        if (index >= arguments.size) {
            return continuation(collected)
        }

        return emitExpression(arguments[index], scope, signature, nameGenerator) { value ->
            emitArguments(arguments, index + 1, scope, signature, nameGenerator, collected + value, continuation)
        }
    }

    /**
     * Parses a MiniKotlin type token into the internal type model.
     */
    private fun parseType(
        type: MiniKotlinParser.TypeContext,
        context: ParserRuleContext,
    ): MiniType =
        when {
            type.INT_TYPE() != null -> MiniType.INT
            type.STRING_TYPE() != null -> MiniType.STRING
            type.BOOLEAN_TYPE() != null -> MiniType.BOOLEAN
            type.UNIT_TYPE() != null -> MiniType.UNIT
            else -> compilerError("Unsupported type '${type.text}'", context)
        }

    /**
     * Validates that a value can be assigned to an expected type.
     */
    private fun ensureAssignable(
        expected: MiniType,
        actual: MiniType,
        message: String,
        context: ParserRuleContext,
    ) {
        if (expected != actual) {
            compilerError(message, context)
        }
    }

    /**
     * Validates that a binary operator receives integer operands.
     */
    private fun ensureIntBinary(
        left: TypedValue,
        right: TypedValue,
        operator: String,
        context: ParserRuleContext,
    ) {
        if (left.type != MiniType.INT || right.type != MiniType.INT) {
            compilerError(
                "Operator '$operator' requires Int operands, but got ${left.type.displayName} and ${right.type.displayName}",
                context,
            )
        }
    }

    /**
     * Validates that Unit values are not used as operands in String concatenation.
     */
    private fun ensureNoUnitForStringConcat(
        value: TypedValue,
        context: ParserRuleContext,
    ) {
        if (value.type == MiniType.UNIT) {
            compilerError("Unit cannot be used in string concatenation", context)
        }
    }

    /**
     * Adapts a typed expression value to a concrete target type.
     */
    private fun adaptValue(
        value: TypedValue,
        targetType: MiniType,
    ): String =
        if (targetType == MiniType.UNIT) {
            "null"
        } else {
            value.expression
        }

    /**
     * Raises a compile-time error with source location details.
     */
    private fun compilerError(
        message: String,
        context: ParserRuleContext,
    ): Nothing {
        val line = context.start?.line ?: 0
        val column = (context.start?.charPositionInLine ?: 0) + 1
        throw IllegalArgumentException("$message (line $line, column $column)")
    }

    /**
     * Indents multiline code fragments by two spaces.
     */
    private fun indent(text: String): String =
        text.lines().joinToString("\n") { line ->
            if (line.isBlank()) line else "  $line"
        }

    /**
     * Normalizes generated Java indentation so mixed snippet indentation still produces clean output.
     */
    private fun formatJava(source: String): String {
        val normalizedLines = mutableListOf<String>()
        var indentLevel = 0

        source.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) {
                normalizedLines += ""
                return@forEach
            }

            val leadingClosings = countLeadingClosingBraces(line)
            indentLevel = (indentLevel - leadingClosings).coerceAtLeast(0)
            normalizedLines += "${"  ".repeat(indentLevel)}$line"

            val openings = line.count { it == '{' }
            val closings = line.count { it == '}' }
            val trailingClosings = (closings - leadingClosings).coerceAtLeast(0)
            indentLevel = (indentLevel + openings - trailingClosings).coerceAtLeast(0)
        }

        return normalizedLines.joinToString("\n")
    }

    /**
     * Counts how many `}` characters appear at the beginning of a trimmed Java code line.
     */
    private fun countLeadingClosingBraces(line: String): Int {
        var index = 0
        while (index < line.length && line[index] == '}') {
            index++
        }
        return index
    }

    /**
     * Uses the active continuation after validating the produced expression value.
     */
    private fun continuationWithValidation(
        value: TypedValue,
        context: ParserRuleContext,
        combine: (TypedValue) -> String,
    ): String {
        if (value.type == MiniType.UNIT) {
            compilerError("Unit cannot be used as a value in this expression", context)
        }
        return combine(value)
    }
}
