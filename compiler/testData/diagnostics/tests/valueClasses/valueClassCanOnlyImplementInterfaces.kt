// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
// ALLOW_KOTLIN_PACKAGE
// SKIP_JAVAC
// LANGUAGE: +InlineClasses

package kotlin.jvm

annotation class JvmInline

abstract class AbstractBaseClass

open class OpenBaseClass

interface BaseInterface

@JvmInline
value class TestExtendsAbstractClass(val x: Int) : <!VALUE_CLASS_CANNOT_EXTEND_CLASSES!>AbstractBaseClass<!>()

@JvmInline
value class TestExtendsOpenClass(val x: Int) : <!VALUE_CLASS_CANNOT_EXTEND_CLASSES!>OpenBaseClass<!>()

@JvmInline
value class TestImplementsInterface(val x: Int) : BaseInterface

/* GENERATED_FIR_TAGS: annotationDeclaration, classDeclaration, interfaceDeclaration, primaryConstructor,
propertyDeclaration, value */
