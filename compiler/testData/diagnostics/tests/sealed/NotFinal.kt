// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
// See KT-9244

sealed class Foo {
  class Bar : Foo()
  class Baz : Foo()
}

// The following warning seems incorrect here
// "Foo is a final type, and thus a value of the type parameter is predetermined"
fun <T : Foo> doit(arg: T): T = arg

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, nestedClass, sealed, typeConstraint, typeParameter */
