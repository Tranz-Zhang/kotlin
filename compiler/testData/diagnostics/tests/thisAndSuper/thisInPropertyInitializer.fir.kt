// RUN_PIPELINE_TILL: FRONTEND
interface Base {
    fun foo()
}
val String.test: Base = <!EXTENSION_PROPERTY_WITH_BACKING_FIELD!>object: Base<!> {
    override fun foo() {
        this<!UNRESOLVED_LABEL!>@test<!>
    }
}

/* GENERATED_FIR_TAGS: anonymousObjectExpression, functionDeclaration, interfaceDeclaration, override,
propertyDeclaration, propertyWithExtensionReceiver, thisExpression */
