// RUN_PIPELINE_TILL: FIR2IR
// ISSUE: KT-68830
// MODULE: m1-common
// FILE: common.kt

open expect class A1() {
    open fun foo(): String
}

expect class B1() : A1

fun test1() = B1().foo()

open class A2() {
    open fun foo(): String = "OK"
}

expect class B2() : A2

fun test2() = B2().foo()

// MODULE: m1-jvm()()(m1-common)
// FILE: jvm.kt

open actual class A1 {
    open actual fun foo(): String = "OK"
}

/* GENERATED_FIR_TAGS: actual, classDeclaration, expect, functionDeclaration, primaryConstructor, stringLiteral */
