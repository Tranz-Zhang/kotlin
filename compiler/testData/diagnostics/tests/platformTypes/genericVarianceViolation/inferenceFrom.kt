// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
// SKIP_TXT
// FILE: JavaClass.java
import org.jetbrains.annotations.NotNull;
public class JavaClass {
    public static void add(final @NotNull java.util.List<? super CharSequence> addedLibraries) {}
}

// FILE: main.kt

fun bar() {
    JavaClass.add(ArrayList())
}

/* GENERATED_FIR_TAGS: flexibleType, functionDeclaration, javaFunction */
