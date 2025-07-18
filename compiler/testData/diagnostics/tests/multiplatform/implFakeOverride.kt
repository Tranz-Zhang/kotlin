// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
// MODULE: m1-common
// FILE: common.kt

expect class Foo {
    fun bar(): String
}

// MODULE: m2-jvm()()(m1-common)
// FILE: jvm.kt

open class Bar {
    fun bar() = "bar"
}

actual class Foo : Bar()

/* GENERATED_FIR_TAGS: actual, classDeclaration, expect, functionDeclaration, stringLiteral */
