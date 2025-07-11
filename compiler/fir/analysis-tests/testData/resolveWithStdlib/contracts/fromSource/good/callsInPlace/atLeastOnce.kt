// RUN_PIPELINE_TILL: FRONTEND
// DUMP_CFG

import kotlin.contracts.*

@OptIn(ExperimentalContracts::class)
inline fun inlineRun(block: () -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.AT_LEAST_ONCE)
    }
    block()
}

@OptIn(ExperimentalContracts::class)
fun myRun(block: () -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.AT_LEAST_ONCE)
    }
    block()
}

fun test_1() {
    val x: Int
    inlineRun {
        <!VAL_REASSIGNMENT!>x<!> = 1
    }
    x.inc()
}

fun test_2() {
    val x: Int
    myRun {
        <!VAL_REASSIGNMENT!>x<!> = 1
    }
    x.inc()
}

/* GENERATED_FIR_TAGS: assignment, classReference, contractCallsEffect, contracts, functionDeclaration, functionalType,
inline, integerLiteral, lambdaLiteral, localProperty, propertyDeclaration */
