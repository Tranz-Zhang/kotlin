// RUN_PIPELINE_TILL: FRONTEND
annotation class B(vararg val args: String)

@B(*<!ARGUMENT_TYPE_MISMATCH!>arrayOf(1, "b")<!>)
fun test() {
}

/* GENERATED_FIR_TAGS: annotationDeclaration, collectionLiteral, functionDeclaration, integerLiteral, outProjection,
primaryConstructor, propertyDeclaration, stringLiteral, vararg */
