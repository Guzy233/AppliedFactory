package com.fulent.appliedfactory.script;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Skips the property entirely when its value is {@code null} at bind time (used with
 * {@link JsReadOnly} / {@link JsInternal}) or when its getter first returns {@code null}
 * (used with {@link JsLive}). Absent properties read as {@code undefined} in scripts.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface JsOptional {
}
