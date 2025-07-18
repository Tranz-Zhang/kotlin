// RUN_PIPELINE_TILL: FRONTEND
// FILE: J.java

import kotlin.jvm.functions.Function1;

public interface J extends Function1<Integer, Void> {
}

// FILE: 1.kt

fun useJ(j: J) {
    j(42)
}

fun jj() {
    useJ(<!ARGUMENT_TYPE_MISMATCH!>{}<!>)
}

/* GENERATED_FIR_TAGS: flexibleType, functionDeclaration, integerLiteral, javaType, lambdaLiteral, samConversion */
