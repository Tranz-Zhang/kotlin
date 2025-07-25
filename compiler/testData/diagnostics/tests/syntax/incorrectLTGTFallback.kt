// RUN_PIPELINE_TILL: BACKEND
// SKIP_TXT
// FIR_IDENTICAL
// ISSUE: KT-53719

@Target(AnnotationTarget.EXPRESSION)
@Retention(AnnotationRetention.SOURCE)
annotation class Ann(val x: String)
fun <T> foo(x: (T) -> T) {}

fun main() {
    foo<Int> label@ { x -> x }
    foo<Int> label@{ x -> x }
    foo<Int>label@ { x -> x }
    foo<Int>label@{ x -> x }
    foo<Int>label@
    { x -> x }

    foo<Int>/* */label@ { x -> x }
    foo<Int>/* */label@{ x -> x }
    foo<Int>label@/* */{ x -> x }
    foo<Int> label@/* */{ x -> x }
    foo<Int> label@/* */
    { x -> x }

    foo<Int> @Ann("") label@ { x -> x }
    foo<Int>/* */@Ann("") label@ { x -> x }
    foo<Int>@Ann("")/* */label@ { x -> x }
    foo<Int> @Ann("") label@/* */{ x -> x }
    foo<Int> @Ann("") label@/* */
    { x -> x }
}

/* GENERATED_FIR_TAGS: annotationDeclaration, functionDeclaration, functionalType, lambdaLiteral, nullableType,
primaryConstructor, propertyDeclaration, stringLiteral, typeParameter */
