// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
// FILE: Foo.kt

package test

class Foo {
    fun bar() {}
}

// FILE: test.kt

import test.Foo

fun Foo(): String = ""

val f = Foo::bar
val g = Foo::<!UNRESOLVED_REFERENCE!>length<!>

/* GENERATED_FIR_TAGS: callableReference, classDeclaration, functionDeclaration, propertyDeclaration, stringLiteral */
