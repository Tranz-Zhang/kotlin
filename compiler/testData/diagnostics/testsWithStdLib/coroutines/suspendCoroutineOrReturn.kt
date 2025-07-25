// RUN_PIPELINE_TILL: FRONTEND
// DIAGNOSTICS: -UNUSED_PARAMETER
// CHECK_TYPE
// SKIP_TXT

import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

class Controller {
    suspend fun noParams(): Unit = suspendCoroutineUninterceptedOrReturn {
        if (hashCode() % 2 == 0) {
            it.resume(Unit)
            COROUTINE_SUSPENDED
        }
        else {
            Unit
        }
    }
    suspend fun yieldString(value: String) = suspendCoroutineUninterceptedOrReturn<Int> {
        it.resume(1)
        it checkType { _<Continuation<Int>>() }
        it.resume(<!TYPE_MISMATCH!>""<!>)

        // We can return anything here, 'suspendCoroutineUninterceptedOrReturn' is not very type-safe
        // Also we can call resume and then return the value too, but it's still just our problem
        "Not-int"
    }
}

fun builder(c: suspend Controller.() -> Unit) {}

fun test() {
    builder {
        noParams() checkType { _<Unit>() }
        yieldString("abc") checkType { _<Int>() }
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, equalityExpression, funWithExtensionReceiver, functionDeclaration,
functionalType, ifExpression, infix, integerLiteral, lambdaLiteral, multiplicativeExpression, nullableType,
stringLiteral, suspend, typeParameter, typeWithExtension */
