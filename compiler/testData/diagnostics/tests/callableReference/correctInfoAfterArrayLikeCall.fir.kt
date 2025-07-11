// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -UNUSED_PARAMETER
/*
 * KOTLIN DIAGNOSTICS SPEC TEST (POSITIVE)
 *
 * SPEC VERSION: 0.1-220
 * PRIMARY LINKS: expressions, call-and-property-access-expressions, navigation-operators -> paragraph 9 -> sentence 2
 * expressions, call-and-property-access-expressions, navigation-operators -> paragraph 8 -> sentence 1
 */

import kotlin.reflect.KProperty1

class DTO {
    val q: Int = 0
    operator fun get(prop: KProperty1<*, Int>): Int = 0
}

fun foo(intDTO: DTO?, p: KProperty1<*, Int>) {
    if (intDTO != null) {
        intDTO[DTO::q]
        intDTO.q
    }
}

/* GENERATED_FIR_TAGS: callableReference, classDeclaration, equalityExpression, functionDeclaration, ifExpression,
integerLiteral, nullableType, operator, propertyDeclaration, smartcast, starProjection */
