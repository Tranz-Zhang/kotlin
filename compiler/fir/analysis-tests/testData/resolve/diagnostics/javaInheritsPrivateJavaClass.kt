// RUN_PIPELINE_TILL: BACKEND
// ISSUE: KT-70157
// LANGUAGE: +ProhibitJavaClassInheritingPrivateKotlinClass
// FILE: Some.java

public class Some {
    public static class Derived extends Base {}

    private static class Base {}
}

// FILE: test.kt

fun main() {
    val d = Some.Derived()
}

/* GENERATED_FIR_TAGS: functionDeclaration, javaFunction, javaType, localProperty, propertyDeclaration */
