// RUN_PIPELINE_TILL: FRONTEND
fun testVarArgs(<!FORBIDDEN_VARARG_PARAMETER_TYPE!>vararg<!> v: <!UNRESOLVED_REFERENCE!>Smth<!>) {}

/* GENERATED_FIR_TAGS: functionDeclaration, vararg */
