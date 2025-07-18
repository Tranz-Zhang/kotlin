// RUN_PIPELINE_TILL: BACKEND
public open class X {
    protected val x : String? = null
    public fun fn(): Int {
        if (x != null)
            // Smartcast is possible for protected value property in the same class
            return x.length
        else
            return 0
    }
}

public class Y: X() {
    public fun bar(): Int {
        // Smartcast is possible even in derived class
        return if (x != null) x.length else 0
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, equalityExpression, functionDeclaration, ifExpression, integerLiteral,
nullableType, propertyDeclaration, smartcast */
