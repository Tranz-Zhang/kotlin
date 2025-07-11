// RUN_PIPELINE_TILL: FRONTEND
import kotlin.reflect.KClass

open class A
class B1 : A()
class B2 : A()

annotation class Ann1(val arg: Array<KClass<out A>>)

@Ann1(arrayOf(A::class))
class MyClass1

@Ann1(<!TYPE_MISMATCH!>arrayOf(<!TYPE_MISMATCH!>Any::class<!>)<!>)
class MyClass1a

@Ann1(arrayOf(B1::class))
class MyClass2

annotation class Ann2(val arg: Array<KClass<out B1>>)

@Ann2(<!TYPE_MISMATCH!>arrayOf(<!TYPE_MISMATCH!>A::class<!>)<!>)
class MyClass3

@Ann2(arrayOf(B1::class))
class MyClass4

@Ann2(<!TYPE_MISMATCH!>arrayOf(<!TYPE_MISMATCH!>B2::class<!>)<!>)
class MyClass5

/* GENERATED_FIR_TAGS: annotationDeclaration, classDeclaration, classReference, collectionLiteral, outProjection,
primaryConstructor, propertyDeclaration */
