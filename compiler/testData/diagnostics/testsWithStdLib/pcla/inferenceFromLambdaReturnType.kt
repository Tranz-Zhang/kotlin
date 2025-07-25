// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
// FIR_DUMP
// ISSUE: KT-57873

class ThemeKey<T>

fun <S> getWithFallback(fallback: (ThemeKey<S>) -> S) {}

fun main() {
    getWithFallback { "" }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, functionalType, lambdaLiteral, nullableType, stringLiteral,
typeParameter */
