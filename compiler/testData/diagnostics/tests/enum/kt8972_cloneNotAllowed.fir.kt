// RUN_PIPELINE_TILL: FRONTEND
enum class E : Cloneable {
    A;
    <!OVERRIDING_FINAL_MEMBER!>override<!> fun clone(): Any {
        return <!AMBIGUOUS_SUPER!>super<!>.clone()
    }
}

/* GENERATED_FIR_TAGS: enumDeclaration, enumEntry, functionDeclaration, override, superExpression */
