// RUN_PIPELINE_TILL: FRONTEND
open class C {
    open val x: Int = 10
    fun h() {}
}

abstract class A : C() {
    override val x: Int = 20

    abstract val y: Int

    abstract fun f()

    fun t() {
        super.h()
        super.x
    }
}

<!ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED!>class B<!> : A() {
    override fun f() {

    }

    fun g() {
        super.<!ABSTRACT_SUPER_CALL!>f<!>()
        super.t()

        super.x
        super.<!ABSTRACT_SUPER_CALL!>y<!>
    }
}

abstract class J : A() {
    fun r() {
        super.<!ABSTRACT_SUPER_CALL!>f<!>()
        super.t()

        super.x
        super.<!ABSTRACT_SUPER_CALL!>y<!>
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, integerLiteral, override, propertyDeclaration,
superExpression */
