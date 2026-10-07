# MiniKotlin to Java CPS Compiler

## Overview

A compiler that translates MiniKotlin (a subset of Kotlin) source code into Java, where all functions are expressed using continuation-passing style.

## Project Structure

- `samples/` - Example MiniKotlin programs
- `src/main/antlr/MiniKotlin.g4` - Grammar definition for MiniKotlin
- `src/main/kotlin/compiler/` - Compiler implementation
- `src/test/` - Testing framework
- `stdlib/` - Standard library with `Prelude` class containing CPS function examples

