// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
// DIAGNOSTICS: -UNUSED_VARIABLE

fun test() {
    fun bar() {
        val bas = fun() {
            <!RETURN_NOT_ALLOWED!>return@bar<!>
        }
    }

    val bar = fun() {
        <!RETURN_NOT_ALLOWED!>return@test<!>
    }
}

fun foo() {
    val bal = bag@ fun () {
        val bar = fun() {
            <!RETURN_NOT_ALLOWED!>return@bag<!>
        }
        return@bag
    }
}

/* GENERATED_FIR_TAGS: anonymousFunction, functionDeclaration, localFunction, localProperty, propertyDeclaration */
