// RUN_PIPELINE_TILL: BACKEND
// See KT-15839

val x = "1".let(@<!DEBUG_INFO_MISSING_UNRESOLVED!>Suppress<!>("DEPRECATION") Integer::parseInt)

/* GENERATED_FIR_TAGS: flexibleType, javaCallableReference, propertyDeclaration, stringLiteral */
