// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
class A<T> {
    fun <S> foo(s: S): S = s
    fun <U> bar(s: U): List<T> = null!!

    fun test() = foo(bar(""))
}

/* GENERATED_FIR_TAGS: checkNotNullCall, classDeclaration, functionDeclaration, nullableType, stringLiteral,
typeParameter */
