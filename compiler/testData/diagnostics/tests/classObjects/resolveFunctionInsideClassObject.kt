// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
package test

class Test {
  fun test(): Int = 12

  companion object {
    val a = <!UNRESOLVED_REFERENCE!>test<!>() // Check if resolver will be able to infer type of a variable
  }
}

/* GENERATED_FIR_TAGS: classDeclaration, companionObject, functionDeclaration, integerLiteral, objectDeclaration,
propertyDeclaration */
