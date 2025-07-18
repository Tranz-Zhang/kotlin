// RUN_PIPELINE_TILL: FRONTEND
// FILE: JavaEnum.java
public enum JavaEnum {
    A, B, C;

    public int i = 0;
}

// FILE: main.kt
fun test_1(e: JavaEnum) {
    val a = <!NO_ELSE_IN_WHEN!>when<!> (e) {
        JavaEnum.A -> 1
        JavaEnum.B -> 2
    }.plus(0)

    val b = <!NO_ELSE_IN_WHEN!>when<!> (e) {
        JavaEnum.A -> 1
        JavaEnum.B -> 2
        <!USELESS_IS_CHECK!>is String<!> -> 3
    }.plus(0)

    val c = when (e) {
        JavaEnum.A -> 1
        JavaEnum.B -> 2
        JavaEnum.C -> 3
    }.plus(0)

    val d = when (e) {
        JavaEnum.A -> 1
        else -> 2
    }.plus(0)
}

fun test_2(e: JavaEnum?) {
    val a = <!NO_ELSE_IN_WHEN!>when<!> (e) {
        JavaEnum.A -> 1
        JavaEnum.B -> 2
        JavaEnum.C -> 3
    }.plus(0)

    val b = when (e) {
        JavaEnum.A -> 1
        JavaEnum.B -> 2
        JavaEnum.C -> 3
        null -> 4
    }.plus(0)

    val c = when (e) {
        JavaEnum.A -> 1
        JavaEnum.B -> 2
        JavaEnum.C -> 3
        else -> 4
    }.plus(0)
}

fun test_3(e: JavaEnum) {
    val a = when (e) {
        JavaEnum.A, JavaEnum.B -> 1
        JavaEnum.C -> 2
    }.plus(0)
}

/* GENERATED_FIR_TAGS: disjunctionExpression, equalityExpression, functionDeclaration, integerLiteral, isExpression,
javaProperty, javaType, localProperty, nullableType, propertyDeclaration, smartcast, whenExpression, whenWithSubject */
