// RUN_PIPELINE_TILL: FRONTEND
interface A

interface B

fun test1(): B = <!TYPE_MISMATCH!>object : A<!> {
}

fun test2(): B = <!TYPE_MISMATCH!>object<!> {
}

/* GENERATED_FIR_TAGS: anonymousObjectExpression, functionDeclaration, interfaceDeclaration */
