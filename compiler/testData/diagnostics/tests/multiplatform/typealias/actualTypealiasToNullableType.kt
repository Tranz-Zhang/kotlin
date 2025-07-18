// FIR_IDENTICAL
// IGNORE_FIR_DIAGNOSTICS
// RUN_PIPELINE_TILL: BACKEND
// MODULE: m1-common
// FILE: common.kt

expect class E01
expect class E02

// MODULE: m1-jvm()()(m1-common)
// FILE: jvm.kt

typealias MyNullable = String?

<!ACTUAL_TYPE_ALIAS_TO_NULLABLE_TYPE!>actual typealias E01 = String?<!>
<!ACTUAL_TYPE_ALIAS_NOT_TO_CLASS!>actual typealias E02 = MyNullable<!>

/* GENERATED_FIR_TAGS: actual, classDeclaration, expect, nullableType, typeAliasDeclaration */
