// RUN_PIPELINE_TILL: BACKEND
// WITH_STDLIB
// ISSUE: KT-50293

fun main() {
    val list = buildList {
        add("one")
        add("two")

        val secondParameter = get(1)
        println(secondParameter <!USELESS_CAST!>as String<!>)
    }
}

/* GENERATED_FIR_TAGS: asExpression, functionDeclaration, integerLiteral, lambdaLiteral, localProperty,
propertyDeclaration, stringLiteral */
