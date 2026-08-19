package com.fulent.appliedfactory.script;

import org.graalvm.polyglot.Value;

/** Small conversion and validation helpers shared by the GraalJS bridge. */
final class JsValues {
    private JsValues() {
    }

    static RuntimeException error(String message) {
        return new IllegalArgumentException(message);
    }

    static boolean isNullish(Object value) {
        return value == null || value == JsBridgeBinder.UNDEFINED
                || value instanceof Value guest && guest.isNull();
    }

    static Object toHost(Value value) {
        if (value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        return value;
    }

    static String string(Object value) {
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Value guest && guest.isString()) {
            return guest.asString();
        }
        return String.valueOf(value);
    }

    static String string(Value value, String name) {
        if (!value.isString()) {
            throw error(name + " requires a string");
        }
        return value.asString();
    }

    static double number(Object value, String name) {
        double number;
        if (value instanceof Number host) {
            number = host.doubleValue();
        } else if (value instanceof Value guest && guest.isNumber()) {
            number = guest.asDouble();
        } else {
            throw error(name + " requires a number");
        }
        if (!Double.isFinite(number)) {
            throw error(name + " requires a finite number");
        }
        return number;
    }

    static Value object(Object value, String name) {
        if (value instanceof Value guest && guest.hasMembers() && !guest.canExecute()) {
            return guest;
        }
        throw error(name + " must be an object");
    }

    static Value array(Object value, String name) {
        if (value instanceof Value guest && guest.hasArrayElements()) {
            return guest;
        }
        throw error(name + " must be an array");
    }

    static Object required(Value object, String name) {
        if (!object.hasMember(name)) {
            throw error("Missing property: " + name);
        }
        return toHost(object.getMember(name));
    }
}
