// RUN_PIPELINE_TILL: FRONTEND
class A0 {
    <!MUST_BE_INITIALIZED_OR_BE_ABSTRACT!>val x: Int<!>
    constructor() {
        if (1 == 1) {
            return
        }
        x = 1
    }
    constructor(arg: Int) {
        x = arg
    }
}

class A1 {
    <!MUST_BE_INITIALIZED_OR_BE_ABSTRACT!>val x: Int<!>
    constructor() {
        if (1 == 1) {
            return
        } else null!!
        x = 1
    }
}

class A2 {
    val x: Int
    constructor() {
        if (1 == 1) {
            x = 1
            return
        }
        else {
            x = 2
        }
    }
    constructor(arg: Int) {
        x = arg
    }
}

/* GENERATED_FIR_TAGS: assignment, checkNotNullCall, classDeclaration, equalityExpression, ifExpression, integerLiteral,
propertyDeclaration, secondaryConstructor */
