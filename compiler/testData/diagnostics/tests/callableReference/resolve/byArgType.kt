// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
// DIAGNOSTICS: -UNUSED_PARAMETER

fun foo() {}
fun foo(s: String) {}

fun fn(f: () -> Unit) {}

fun test() {
    fn(::foo)
}

/* GENERATED_FIR_TAGS: callableReference, functionDeclaration, functionalType */
