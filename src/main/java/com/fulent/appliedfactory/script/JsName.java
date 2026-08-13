package com.fulent.appliedfactory.script;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the JavaScript name of an exposed facade method, so a Java method
 * whose name cannot be used verbatim (e.g. {@code breakBlock} exposed as
 * {@code break}, a Java keyword) or that should differ from the Java name can
 * still be called from scripts.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JsName {
    String value();
}
