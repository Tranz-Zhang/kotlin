// RUN_PIPELINE_TILL: FRONTEND
// MODULE: m1
// FILE: a.kt

package p

private val a = 1

// MODULE: m2(m1)
// FILE: c.kt

package p

val c = <!INVISIBLE_REFERENCE!>a<!> // same package, another module

/* GENERATED_FIR_TAGS: integerLiteral, propertyDeclaration */
