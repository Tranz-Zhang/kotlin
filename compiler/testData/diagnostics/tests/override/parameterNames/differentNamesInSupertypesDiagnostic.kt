// FIR_IDENTICAL
// RUN_PIPELINE_TILL: BACKEND
// RENDER_DIAGNOSTICS_FULL_TEXT
interface C {
    fun foo(a : Int)
}

interface D {
    fun foo(b : Int)
}

<!DIFFERENT_NAMES_FOR_THE_SAME_PARAMETER_IN_SUPERTYPES!>interface E<!> : C, D

interface F : C, D {
    override fun foo(<!PARAMETER_NAME_CHANGED_ON_OVERRIDE!>a<!> : Int) {
        throw UnsupportedOperationException()
    }
}

/* GENERATED_FIR_TAGS: functionDeclaration, interfaceDeclaration, override */
