// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
val Int.foo: Int
    get() = this


fun test(foo: Int) {
    test(4.foo)
    test(foo)
}

/* GENERATED_FIR_TAGS: functionDeclaration, getter, integerLiteral, propertyDeclaration, propertyWithExtensionReceiver,
thisExpression */
