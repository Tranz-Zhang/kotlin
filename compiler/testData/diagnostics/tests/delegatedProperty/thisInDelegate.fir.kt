// RUN_PIPELINE_TILL: FRONTEND
// DIAGNOSTICS: -UNUSED_PARAMETER

import kotlin.reflect.KProperty

val Int.a by Delegate(<!NO_THIS!>this<!>)

class A {
  val Int.a by Delegate(<!ARGUMENT_TYPE_MISMATCH!>this<!>)
}

class Delegate(i: Int) {
  operator fun getValue(t: Any?, p: KProperty<*>): Int {
    return 1
  }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, integerLiteral, nullableType, operator, primaryConstructor,
propertyDeclaration, propertyDelegate, propertyWithExtensionReceiver, starProjection, thisExpression */
