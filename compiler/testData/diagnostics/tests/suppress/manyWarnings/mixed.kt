// RUN_PIPELINE_TILL: BACKEND
@Suppress("REDUNDANT_NULLABLE")
class C {
    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
    fun foo(): String?? = ""!! <!USELESS_CAST!>as String??<!>
}

/* GENERATED_FIR_TAGS: asExpression, checkNotNullCall, classDeclaration, functionDeclaration, nullableType,
stringLiteral */
