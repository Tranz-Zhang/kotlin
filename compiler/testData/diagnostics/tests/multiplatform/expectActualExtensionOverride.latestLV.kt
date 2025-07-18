// IGNORE_FIR_DIAGNOSTICS
// LATEST_LV_DIFFERENCE
// RUN_PIPELINE_TILL: BACKEND
// MODULE: m1-common
// FILE: common.kt
interface Base {
    fun foo(a: Int): String
    val a : String
}

interface ExtensionBase {
    fun Int.foo(): String
    val Int.a : String
}

expect <!ABSTRACT_MEMBER_NOT_IMPLEMENTED{METADATA}!>class A<!> : Base

expect <!ABSTRACT_MEMBER_NOT_IMPLEMENTED{METADATA}!>class B<!> : ExtensionBase

// MODULE: m2-jvm()()(m1-common)
// FILE: jvm.kt
actual <!ABSTRACT_MEMBER_NOT_IMPLEMENTED!>class A<!> : Base {
    <!NOTHING_TO_OVERRIDE!>override<!> fun Int.foo(): String {
        return ""
    }
    <!NOTHING_TO_OVERRIDE!>override<!> val Int.a: String
        get() = ""
}

actual <!ABSTRACT_MEMBER_NOT_IMPLEMENTED!>class B<!> : ExtensionBase {
    <!NOTHING_TO_OVERRIDE!>override<!> fun foo(a: Int): String {
        return ""
    }
    <!NOTHING_TO_OVERRIDE!>override<!> val a: String = ""
}

/* GENERATED_FIR_TAGS: actual, classDeclaration, expect, funWithExtensionReceiver, functionDeclaration, getter,
interfaceDeclaration, override, propertyDeclaration, propertyWithExtensionReceiver, stringLiteral */
