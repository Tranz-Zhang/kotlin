// IGNORE_FIR_DIAGNOSTICS
// RUN_PIPELINE_TILL: BACKEND
// ISSUE: KT-45848
// MODULE: m1-common

sealed class Base

class Derived : Base()

fun test_1(b: Base) = when (b) {
    is Derived -> 1
}

// MODULE: m1-jvm()()(m1-common)

class PlatfromDerived : <!SEALED_INHERITOR_IN_DIFFERENT_MODULE!>Base<!>() // must be an error

fun test_2(b: Base) = when (b) {
    is Derived -> 1
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, integerLiteral, isExpression, sealed, whenExpression,
whenWithSubject */
