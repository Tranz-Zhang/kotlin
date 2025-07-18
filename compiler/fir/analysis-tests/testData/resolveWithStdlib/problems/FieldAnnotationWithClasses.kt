// RUN_PIPELINE_TILL: BACKEND
// WITH_REFLECT

import kotlin.reflect.*

annotation class Ann(vararg val allowedTypes: KClass<*>)

fun foo() {
    class Local {
        @field:Ann(allowedTypes = [Some::class, Other::class])
        val x: Int = 42
    }
}

class Some
class Other

/* GENERATED_FIR_TAGS: annotationDeclaration, annotationUseSiteTargetField, classDeclaration, classReference,
collectionLiteral, functionDeclaration, integerLiteral, localClass, outProjection, primaryConstructor,
propertyDeclaration, starProjection, vararg */
