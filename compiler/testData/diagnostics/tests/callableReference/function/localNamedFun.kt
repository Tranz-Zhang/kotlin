// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
// CHECK_TYPE

import kotlin.reflect.*

fun main() {
    fun foo() {}
    fun bar(x: Int) {}
    fun baz() = "OK"

    val x = ::foo
    val y = ::bar
    val z = ::baz

    checkSubtype<KFunction0<Unit>>(x)
    checkSubtype<KFunction1<Int, Unit>>(y)
    checkSubtype<KFunction0<String>>(z)
}

/* GENERATED_FIR_TAGS: callableReference, classDeclaration, funWithExtensionReceiver, functionDeclaration,
functionalType, infix, localFunction, localProperty, nullableType, propertyDeclaration, stringLiteral, typeParameter,
typeWithExtension */
