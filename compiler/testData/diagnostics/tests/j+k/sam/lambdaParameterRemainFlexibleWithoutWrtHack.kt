// RUN_PIPELINE_TILL: FRONTEND
// LANGUAGE: -JavaTypeParameterDefaultRepresentationWithDNN +DontMakeExplicitJavaTypeArgumentsFlexible
// ISSUE: KT-67999

// FILE: J.java
public interface J<X> {
    void foo(X x);
}

// FILE: main.kt

fun main() {
    J<String?> { x ->
        x.length // Should not be unsafe call
    }
}

/* GENERATED_FIR_TAGS: functionDeclaration, javaType, lambdaLiteral, nullableType */
