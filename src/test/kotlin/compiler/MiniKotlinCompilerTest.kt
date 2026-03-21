package org.example.compiler

import MiniKotlinLexer
import MiniKotlinParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiniKotlinCompilerTest {
    @TempDir
    lateinit var tempDir: Path

    private fun parseFile(path: Path): MiniKotlinParser.ProgramContext {
        val input = CharStreams.fromPath(path)
        val lexer = MiniKotlinLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = MiniKotlinParser(tokens)
        return parser.program()
    }

    private fun resolveStdlibPath(): Path? {
        val devPath = Paths.get("build", "stdlib")
        if (devPath.toFile().exists()) {
            val stdlibJar =
                devPath
                    .toFile()
                    .listFiles()
                    ?.firstOrNull { it.name.startsWith("stdlib") && it.name.endsWith(".jar") }
            if (stdlibJar != null) return stdlibJar.toPath()
        }
        return null
    }

    private fun sampleMiniPath(name: String): Path = Paths.get("samples", "$name.mini")

    private fun sampleExpectedPath(name: String): Path = Paths.get("samples", "$name.expected.txt")

    private fun compileAndRunFile(
        path: Path,
        className: String = "MiniProgram",
    ): Pair<CompilationResult, ExecutionResult?> {
        val compiler = MiniKotlinCompiler()
        val program = parseFile(path)
        val javaCode = compiler.compile(program, className)
        val javaFile = tempDir.resolve("$className.java")
        Files.writeString(javaFile, javaCode)

        val javaCompiler = JavaRuntimeCompiler()
        val stdlibPath = resolveStdlibPath()
        return javaCompiler.compileAndExecute(javaFile, stdlibPath)
    }

    private fun readExpectedLines(name: String): List<String> =
        Files
            .readAllLines(sampleExpectedPath(name))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun readExpectedFragment(
        name: String,
        header: String,
    ): String {
        val lines = readExpectedLines(name)

        if (lines.size < 2 || lines.first() != header) {
            error("Invalid error fixture format in ${sampleExpectedPath(name)}")
        }

        return lines[1]
    }

    private fun readExpectedErrorFragment(name: String): String =
        readExpectedFragment(name, "Compile error must contain:")

    private fun assertSuccessfulExecution(result: Pair<CompilationResult, ExecutionResult?>): List<String> {
        val (compilationResult, executionResult) = result
        assertIs<CompilationResult.Success>(compilationResult)
        assertIs<ExecutionResult.Success>(executionResult)
        val success = assertNotNull(executionResult)
        return success.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    @Test
    fun `compile example_mini outputs 120 and 15`() {
        val lines = assertSuccessfulExecution(compileAndRunFile(sampleMiniPath("example")))
        assertEquals(readExpectedLines("example"), lines)
    }

    @Test
    fun `expression semantics preserve precedence and short circuit`() {
        val lines = assertSuccessfulExecution(compileAndRunFile(sampleMiniPath("expression-semantics"), "ExprProgram"))
        assertEquals(readExpectedLines("expression-semantics"), lines)
    }

    @Test
    fun `function call arguments are evaluated left to right`() {
        val lines = assertSuccessfulExecution(compileAndRunFile(sampleMiniPath("function-call-order"), "CallOrderProgram"))
        assertEquals(readExpectedLines("function-call-order"), lines)
    }

    @Test
    fun `control flow supports while assignment and branch returns`() {
        val lines = assertSuccessfulExecution(compileAndRunFile(sampleMiniPath("control-flow"), "ControlFlowProgram"))
        assertEquals(readExpectedLines("control-flow"), lines)
    }

    @Test
    fun `type paths support string boolean and unit`() {
        val lines = assertSuccessfulExecution(compileAndRunFile(sampleMiniPath("type-paths"), "TypePathProgram"))
        assertEquals(readExpectedLines("type-paths"), lines)
    }

    @Test
    fun `complex sample compiles and matches expected output`() {
        val lines = assertSuccessfulExecution(compileAndRunFile(sampleMiniPath("complex"), "ComplexProgram"))
        assertEquals(readExpectedLines("complex"), lines)
    }

    @Test
    fun `shadowing sample compiles and matches expected output`() {
        val lines = assertSuccessfulExecution(compileAndRunFile(sampleMiniPath("shadowing"), "ShadowingProgram"))
        assertEquals(readExpectedLines("shadowing"), lines)
    }

    @Test
    fun `compilation result failure sample produces compilationerror list`() {
        val expectedFragment =
            readExpectedFragment(
                name = "compilation-result-failure",
                header = "CompilationResult failure must contain:",
            )
        val (compilationResult, executionResult) =
            compileAndRunFile(sampleMiniPath("compilation-result-failure"), "CompilationResultFailureProgram")

        val failure = assertIs<CompilationResult.Failure>(compilationResult)
        assertNull(executionResult)
        assertTrue(failure.errors.isNotEmpty())

        val firstError = assertNotNull(failure.errors.firstOrNull())
        assertTrue(firstError.line >= 1)
        assertTrue(firstError.column >= 1)
        assertTrue(firstError.message.contains(expectedFragment))
    }

    @Test
    fun `execution result failure sample returns failure result`() {
        val expectedFragment =
            readExpectedFragment(
                name = "execution-result-failure",
                header = "ExecutionResult failure must contain:",
            )
        val (compilationResult, executionResult) =
            compileAndRunFile(sampleMiniPath("execution-result-failure"), "ExecutionResultFailureProgram")

        assertIs<CompilationResult.Success>(compilationResult)
        val failure = assertIs<ExecutionResult.Failure>(executionResult)
        assertTrue(failure.error.contains(expectedFragment))
    }

    @Test
    fun `unknown function call fails compilation`() {
        val path = sampleMiniPath("unknown-function-error")
        val expectedFragment = readExpectedErrorFragment("unknown-function-error")

        val compiler = MiniKotlinCompiler()
        val exception =
            assertFailsWith<IllegalArgumentException> {
                compiler.compile(parseFile(path), "UnknownCallProgram")
            }
        assertEquals(exception.message?.contains(expectedFragment), true)
    }

    @Test
    fun `invalid return type fails compilation`() {
        val path = sampleMiniPath("invalid-return-error")
        val expectedFragment = readExpectedErrorFragment("invalid-return-error")

        val compiler = MiniKotlinCompiler()
        val exception =
            assertFailsWith<IllegalArgumentException> {
                compiler.compile(parseFile(path), "BadReturnProgram")
            }
        assertEquals(exception.message?.contains(expectedFragment), true)
    }
}
