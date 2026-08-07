package com.fulent.appliedfactory.script;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or no-arg getter as a fixed read-only property. The value is captured once when
 * the template is bound and never changes.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface JsReadOnly {
    /** Explicit JS property name; empty derives the JavaBeans name. */
    String name() default "";
}
