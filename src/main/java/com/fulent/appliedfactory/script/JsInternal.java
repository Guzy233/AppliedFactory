package com.fulent.appliedfactory.script;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or no-arg getter as a non-enumerable read-only property. Used for the durable
 * string handles ({@code __factory*}) that survive continuation serialization and drive handle
 * resolution in {@link RuntimeBridge}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface JsInternal {
    /** Explicit JS property name; empty derives the JavaBeans name. */
    String name() default "";
}
