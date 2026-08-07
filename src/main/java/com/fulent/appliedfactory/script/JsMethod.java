package com.fulent.appliedfactory.script;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.mozilla.javascript.Context;

/**
 * Marks a method as a callable read-only property. Two signatures are supported:
 * {@code Object method(Context cx, Object[] args)} for plain bridge functions, or
 * {@code Object method(Context cx, Scriptable receiver, Object[] args)} for shared prototype
 * methods that need the JavaScript receiver. Argument coercion and arity checks stay inside the
 * Java method, exactly like the hand-written bridge functions they replace.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JsMethod {
    /** Explicit JS property name; empty uses the Java method name. */
    String name() default "";
}
