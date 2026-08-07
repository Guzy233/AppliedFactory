package com.fulent.appliedfactory.script;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * Binds plain Java template objects onto JavaScript objects. Annotated members are reflected once
 * and cached per class; every bind produces a JS object whose properties keep the same contracts
 * the rest of the bridge relies on: fixed read-only values, read-only live getters (re-invoked on
 * each read), non-enumerable internal handles, and callable methods.
 *
 * <p>Live getters are invoked through the template instance on every JS read, so a template that
 * references the {@link RuntimeBridge} (whose {@code context} is rebound before every resume)
 * yields live data just like the hand-written suppliers they replace. Bound Java templates are not
 * serializable, so live properties must only be attached to objects excluded from continuation
 * serialization by name (the context object).
 */
public final class Jsify {
    private static final int FIXED = 0;
    private static final int LIVE = 1;
    private static final int INTERNAL = 2;
    private static final int FUNCTION = 3;

    private static final Map<Class<?>, MemberBindings> CACHE = new ConcurrentHashMap<>();

    private Jsify() {
    }

    /** Builds a fresh JS object for {@code template}, optionally wired to {@code prototype}. */
    public static Scriptable toScriptable(
            Context cx, Scriptable scope, Scriptable prototype, Object template) {
        var result = cx.newObject(scope);
        if (prototype != null) {
            result.setPrototype(prototype);
        }
        bind(result, template);
        return result;
    }

    private static void bind(Scriptable target, Object template) {
        if (!(target instanceof ScriptableObject scriptable)) {
            throw new IllegalStateException("Factory script objects must support fixed properties");
        }
        var bindings = CACHE.computeIfAbsent(template.getClass(), Jsify::scan);
        for (var member : bindings.members) {
            switch (member.kind) {
                case FIXED -> {
                    var value = member.invoke(template);
                    if (member.optional && value == null) {
                        continue;
                    }
                    defineReadOnly(scriptable, member.name, value);
                }
                case INTERNAL -> {
                    var value = member.invoke(template);
                    if (member.optional && value == null) {
                        continue;
                    }
                    defineInternal(scriptable, member.name, value);
                }
                case LIVE -> {
                    if (member.optional && member.invoke(template) == null) {
                        continue;
                    }
                    defineLive(scriptable, member.name, () -> member.invoke(template));
                }
                case FUNCTION -> {
                    if (member.receiverAware) {
                        defineMethod(scriptable, member.name,
                                (cx, self, args) -> member.invoke(template, cx, self, args));
                    } else {
                        defineFunction(scriptable, member.name,
                                (cx, args) -> member.invoke(template, cx, args));
                    }
                }
                default -> throw new IllegalStateException("Unknown factory member kind");
            }
        }
    }

    private static MemberBindings scan(Class<?> type) {
        var members = new ArrayList<Member>();
        for (var method : type.getMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            var live = method.getAnnotation(JsLive.class);
            if (live != null) {
                requireArity(method, 0, JsLive.class.getSimpleName());
                members.add(new Member(LIVE, name(method, live.name()), optional(method), false, null, method));
                continue;
            }
            var readOnly = method.getAnnotation(JsReadOnly.class);
            if (readOnly != null) {
                requireArity(method, 0, JsReadOnly.class.getSimpleName());
                members.add(new Member(FIXED, name(method, readOnly.name()), optional(method), false, null, method));
                continue;
            }
            var internal = method.getAnnotation(JsInternal.class);
            if (internal != null) {
                requireArity(method, 0, JsInternal.class.getSimpleName());
                members.add(new Member(INTERNAL, name(method, internal.name()), optional(method), false, null, method));
                continue;
            }
            var call = method.getAnnotation(JsMethod.class);
            if (call != null) {
                var receiverAware = requireCallSignature(method);
                members.add(new Member(
                        FUNCTION, name(method, call.name()), false, receiverAware, null, method));
            }
        }
        for (var current = type; current != null; current = current.getSuperclass()) {
            // Record components annotate both the field and the accessor; bind via the accessor
            // only, or every property would be defined twice.
            if (current.isRecord()) {
                continue;
            }
            for (var field : current.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                var readOnly = field.getAnnotation(JsReadOnly.class);
                if (readOnly != null) {
                    field.trySetAccessible();
                    members.add(new Member(FIXED, name(field, readOnly.name()), optional(field), false, field, null));
                    continue;
                }
                var internal = field.getAnnotation(JsInternal.class);
                if (internal != null) {
                    field.trySetAccessible();
                    members.add(new Member(INTERNAL, name(field, internal.name()), optional(field), false, field, null));
                }
            }
        }
        return new MemberBindings(members);
    }

    // ---- Property definition -------------------------------------------------

    public static void defineReadOnly(Scriptable object, String name, Object value) {
        if (object instanceof ScriptableObject scriptable) {
            scriptable.defineProperty(
                    name, value, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
            return;
        }
        throw new IllegalStateException("Factory script objects must support fixed properties");
    }

    /**
     * A read-only property whose value is recomputed from the bound template on every read. The
     * setter rejects writes. Live slots are never persisted: the objects carrying them are excluded
     * from continuation serialization by name.
     */
    public static void defineLive(Scriptable object, String name, Supplier<Object> getter) {
        if (object instanceof ScriptableObject scriptable) {
            scriptable.defineProperty(
                    name,
                    getter,
                    value -> {
                        throw Context.reportRuntimeError(name + " is read-only");
                    },
                    ScriptableObject.PERMANENT);
            return;
        }
        throw new IllegalStateException("Factory script objects must support fixed properties");
    }

    public static void defineInternal(Scriptable object, String name, Object value) {
        if (object instanceof ScriptableObject scriptable) {
            scriptable.defineProperty(
                    name,
                    value,
                    ScriptableObject.READONLY
                            | ScriptableObject.PERMANENT
                            | ScriptableObject.DONTENUM);
            return;
        }
        throw new IllegalStateException("Factory script objects must support internal properties");
    }

    public static void defineFunction(Scriptable object, String name, JsCall call) {
        defineReadOnly(object, name, function(call));
    }

    /** A callable property that receives the JavaScript receiver (used on shared prototypes). */
    public static void defineMethod(Scriptable object, String name, JsReceiverCall call) {
        defineReadOnly(object, name, new BaseFunction() {
            private static final long serialVersionUID = 1L;

            @Override
            public Object call(
                    Context context,
                    Scriptable callScope,
                    Scriptable thisObject,
                    Object[] args) {
                return call.call(context, thisObject, args);
            }
        });
    }

    public static BaseFunction function(JsCall call) {
        return new BaseFunction() {
            private static final long serialVersionUID = 1L;

            @Override
            public Object call(
                    Context context,
                    Scriptable callScope,
                    Scriptable thisObject,
                    Object[] args) {
                return call.call(context, args);
            }
        };
    }

    @FunctionalInterface
    public interface JsCall {
        Object call(Context context, Object[] args);
    }

    @FunctionalInterface
    public interface JsReceiverCall {
        Object call(Context context, Scriptable receiver, Object[] args);
    }

    // ---- Reflection helpers --------------------------------------------------

    private static void requireArity(Method method, int parameters, String annotation) {
        if (method.getParameterCount() != parameters) {
            throw new IllegalStateException(annotation + " requires a getter with no parameters: "
                    + method.getDeclaringClass().getName() + "#" + method.getName());
        }
    }

    /** Returns true when the callable is receiver-aware ((Context, Scriptable, Object[])). */
    private static boolean requireCallSignature(Method method) {
        var types = method.getParameterTypes();
        if (types.length == 2 && types[0] == Context.class && types[1] == Object[].class) {
            return false;
        }
        if (types.length == 3 && types[0] == Context.class && types[1] == Scriptable.class
                && types[2] == Object[].class) {
            return true;
        }
        throw new IllegalStateException(JsMethod.class.getSimpleName()
                + " requires a method (Context, Object[]) or (Context, Scriptable, Object[]): "
                + method.getDeclaringClass().getName() + "#" + method.getName());
    }

    private static String name(Method method, String override) {
        return override.isEmpty() ? propertyName(method.getName()) : override;
    }

    private static String name(Field field, String override) {
        return override.isEmpty() ? field.getName() : override;
    }

    private static String propertyName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return decapitalize(methodName.substring(3));
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return decapitalize(methodName.substring(2));
        }
        return methodName;
    }

    private static String decapitalize(String value) {
        return value.isEmpty()
                ? value
                : Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static boolean optional(Method method) {
        return method.getAnnotation(JsOptional.class) != null;
    }

    private static boolean optional(Field field) {
        return field.getAnnotation(JsOptional.class) != null;
    }

    private static final class Member {
        private final int kind;
        private final String name;
        private final boolean optional;
        private final boolean receiverAware;
        @Nullable
        private final Field field;
        @Nullable
        private final Method method;

        private Member(
                int kind, String name, boolean optional, boolean receiverAware,
                @Nullable Field field, @Nullable Method method) {
            this.kind = kind;
            this.name = name;
            this.optional = optional;
            this.receiverAware = receiverAware;
            this.field = field;
            this.method = method;
            if (method != null) {
                method.trySetAccessible();
            }
        }

        private Object invoke(Object target) {
            if (field != null) {
                return invokeField(field, target);
            }
            return invokeMethod(method, target);
        }

        private Object invoke(Object target, Object... args) {
            return invokeMethod(method, target, args);
        }
    }

    private static final class MemberBindings {
        private final List<Member> members;

        private MemberBindings(List<Member> members) {
            this.members = List.copyOf(members);
        }
    }

    private static Object invokeField(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Factory template field is not accessible: " + field.getName(), exception);
        }
    }

    private static Object invokeMethod(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Factory template method is not accessible: " + method.getName(), exception);
        } catch (InvocationTargetException exception) {
            var cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException("Factory template method failed: " + method.getName(), cause);
        }
    }
}
