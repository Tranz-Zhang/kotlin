// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
// DIAGNOSTICS: -UNUSED_VARIABLE
//KT-3920 Labeling information is lost when passing through some expressions

fun test() {
    run f@{
        val x = if (1 > 2) return@f 1 else 2
        2
    }
}

/* GENERATED_FIR_TAGS: comparisonExpression, functionDeclaration, ifExpression, integerLiteral, lambdaLiteral,
localProperty, propertyDeclaration */
