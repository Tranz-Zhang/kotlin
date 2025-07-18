// RUN_PIPELINE_TILL: BACKEND
/*
 * Here we desugar b.text += "" into b.text = b.text + ""
 *                                    ^1       ^2
 * Problem is that += resolve expects that (2) will be resolved to
 *   property symbol, but for java synthetics b.text resolves
 *   to function `getText()`
 */

// FILE: B.java

public class B {
    public void setText(String text) {}
    public String getText() {
        return "";
    }
}

// FILE: main.kt

fun test(b: B) {
    b.text += ""
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, flexibleType, functionDeclaration, javaProperty, javaType,
localProperty, propertyDeclaration, stringLiteral */
