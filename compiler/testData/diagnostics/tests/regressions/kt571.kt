// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
//KT-571 Type inference failed
fun <T, R> let(t : T, body : (T) -> R) = body(t)
private fun double(d : Int) : Int = let(d * 2) {it / 10 + it * 2 % 10}

/* GENERATED_FIR_TAGS: additiveExpression, functionDeclaration, functionalType, integerLiteral, lambdaLiteral,
multiplicativeExpression, nullableType, typeParameter */
