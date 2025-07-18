// RUN_PIPELINE_TILL: FRONTEND
// CHECK_TYPE
object Outer {
    private var x
        get() = object : CharSequence {
            override val length: Int
                get() = 0

            override fun get(index: Int): Char {
                checkSubtype<CharSequence>(<!DEBUG_INFO_MISSING_UNRESOLVED, TYPECHECKER_HAS_RUN_INTO_RECURSIVE_PROBLEM_ERROR!>x<!>)
                return ' '
            }

            override fun subSequence(startIndex: Int, endIndex: Int) = ""

            fun bar() {
            }
        }
        set(q) {
            checkSubtype<CharSequence>(x)
            y = q
            x = q
        }

    private var y = <!DEBUG_INFO_LEAKING_THIS!>x<!>

    fun foo() {
        x = y

        checkSubtype<CharSequence>(x)
        checkSubtype<CharSequence>(y)

        x.bar()
        y.bar()
    }
}

/* GENERATED_FIR_TAGS: anonymousObjectExpression, assignment, classDeclaration, funWithExtensionReceiver,
functionDeclaration, functionalType, getter, infix, integerLiteral, nullableType, objectDeclaration, operator, override,
propertyDeclaration, setter, stringLiteral, typeParameter, typeWithExtension */
