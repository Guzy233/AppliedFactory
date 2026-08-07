package com.fulent.appliedfactory.script;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a no-arg getter as a read-only live property: the getter is re-invoked on every JS read,
 * so a template referencing the currently bound bridge state yields fresh data on each access.
 *
 * <p>Live properties must only be attached to objects excluded from continuation serialization by
 * name (the context object); a bound Java template is never serializable.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JsLive {
    /** Explicit JS property name; empty derives the JavaBeans name. */
    String name() default "";
}
