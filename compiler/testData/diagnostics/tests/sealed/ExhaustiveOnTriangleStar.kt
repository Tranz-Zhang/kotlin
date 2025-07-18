// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
sealed class A
sealed class B : A()

class C : B()
class D : B()

fun test(a: A): Any {
    return when (a) {
        is C -> ""
        is D -> ""
    }
}

fun test2(a: A): Any {
    return when (a) {
        is B -> ""
    }
}

fun test3(a: A): Any {
    return <!NO_ELSE_IN_WHEN!>when<!> (a) {
        is D -> ""
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, isExpression, sealed, smartcast, stringLiteral,
whenExpression, whenWithSubject */
