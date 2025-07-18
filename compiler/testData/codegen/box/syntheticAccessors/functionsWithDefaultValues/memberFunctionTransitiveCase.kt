// FILE: A.kt
class A {
    private fun foo(x: String = "OK") = x
    private inline fun bar() = foo()
    internal inline fun baz() = bar()
}

// FILE: B.kt
fun box() : String {
    return A().baz()
}