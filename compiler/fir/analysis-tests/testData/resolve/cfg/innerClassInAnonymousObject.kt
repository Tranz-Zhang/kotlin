// RUN_PIPELINE_TILL: FRONTEND
// DUMP_CFG

val x = object {
    <!NESTED_CLASS_NOT_ALLOWED!>class Nested<!> {
        fun foo() {}
    }
}

/* GENERATED_FIR_TAGS: anonymousObjectExpression, classDeclaration, functionDeclaration, localClass, nestedClass,
propertyDeclaration */
