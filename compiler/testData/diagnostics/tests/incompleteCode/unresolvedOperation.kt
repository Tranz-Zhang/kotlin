// RUN_PIPELINE_TILL: FRONTEND
fun foo(a: Int) {
    <!DEBUG_INFO_MISSING_UNRESOLVED!>!<!><!UNRESOLVED_REFERENCE!>bbb<!>
    <!UNRESOLVED_REFERENCE!>bbb<!> <!DEBUG_INFO_MISSING_UNRESOLVED!>+<!> a
}

/* GENERATED_FIR_TAGS: additiveExpression, functionDeclaration */
