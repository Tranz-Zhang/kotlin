// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
// SKIP_TXT

// FILE: Promise.java
public interface Promise<T> {}

// FILE: CancellablePromise.java
public interface CancellablePromise<E> extends Promise<E> {}

// FILE: main.kt
fun foo(x: Promise<String?>) {
    bar(x as CancellablePromise)
}
fun bar(x: CancellablePromise<String?>) {}

/* GENERATED_FIR_TAGS: asExpression, functionDeclaration, javaType, nullableType */
