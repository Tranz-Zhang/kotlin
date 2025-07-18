// RUN_PIPELINE_TILL: FRONTEND
data class SomeObject(val n: SomeObject?) {
    fun doSomething() {}
    fun next(): SomeObject? = n    
}


fun list(start: SomeObject): SomeObject {
    var e: SomeObject? = start
    for (i in 0..42) {
        // Unsafe calls because of nullable e at the beginning
        e<!UNSAFE_CALL!>.<!>doSomething()
        e = e<!UNSAFE_CALL!>.<!>next()
    }
    // Smart cast is not possible here due to next()
    return <!TYPE_MISMATCH!>e<!>
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, data, forLoop, functionDeclaration, integerLiteral, localProperty,
nullableType, primaryConstructor, propertyDeclaration, rangeExpression */
