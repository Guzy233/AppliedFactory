package com.fulent.appliedfactory.script;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/** Small allow-list binder for dedicated facade classes. */
final class JsBridgeBinder {
    private final Scriptable scope;

    JsBridgeBinder(Scriptable scope) {
        this.scope = scope;
    }

    void installGlobals(Object facade) {
        requireBridge(facade.getClass());
        for (var method : exposedMethods(facade.getClass())) {
            if (method.getAnnotation(JsProperty.class) != null) {
                throw new IllegalStateException("Global API cannot expose properties: " + method);
            }
            ScriptableObject.putProperty(scope, method.getName(), function(facade, method));
        }
    }

    Object wrap(Object value) {
        if (value == null || value == Undefined.instance
                || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Scriptable) {
            return value;
        }
        if (value instanceof Collection<?> collection) {
            var converted = collection.stream().map(this::wrap).toArray();
            return Context.getCurrentContext().newArray(scope, converted);
        }
        if (value instanceof Map<?, ?> map) {
            var object = Context.getCurrentContext().newObject(scope);
            for (var entry : map.entrySet()) {
                ScriptableObject.putProperty(
                        object, Context.toString(entry.getKey()), wrap(entry.getValue()));
            }
            return object;
        }
        if (value.getClass().isArray()) {
            var length = java.lang.reflect.Array.getLength(value);
            var converted = new Object[length];
            for (int index = 0; index < length; index++) {
                converted[index] = wrap(java.lang.reflect.Array.get(value, index));
            }
            return Context.getCurrentContext().newArray(scope, converted);
        }
        requireBridge(value.getClass());
        var object = new BridgeObject(value);
        object.setParentScope(scope);
        object.setPrototype(ScriptableObject.getObjectPrototype(scope));
        for (var method : exposedMethods(value.getClass())) {
            var property = method.getAnnotation(JsProperty.class);
            if (property == null) {
                object.defineProperty(
                        method.getName(),
                        function(value, method),
                        ScriptableObject.READONLY | ScriptableObject.PERMANENT);
                continue;
            }
            if (method.getParameterCount() != 0) {
                throw new IllegalStateException("JS property must have no parameters: " + method);
            }
            var name = property.value().isBlank()
                    ? propertyName(method.getName())
                    : property.value();
            object.defineProperty(
                    name,
                    () -> invoke(value, method, new Object[0]),
                    ignored -> {
                        throw Context.reportRuntimeError(name + " is read-only");
                    },
                    ScriptableObject.PERMANENT);
        }
        return object;
    }

    <T> T unwrap(Object value, Class<T> type, String name) {
        if (!(value instanceof Scriptable scriptable)) {
            throw Context.reportRuntimeError(name + " must be a factory API object");
        }
        var delegate = scriptable instanceof BridgeObject bridge ? bridge.delegate : null;
        if (!type.isInstance(delegate)) {
            throw Context.reportRuntimeError(name + " has the wrong factory API type");
        }
        return type.cast(delegate);
    }

    Object delegate(Object value) {
        return value instanceof BridgeObject bridge ? bridge.delegate : null;
    }

    private BaseFunction function(Object target, Method method) {
        var function = new BaseFunction() {
            private static final long serialVersionUID = 1L;

            @Override
            public Object call(
                    Context context,
                    Scriptable callScope,
                    Scriptable thisObject,
                    Object[] args) {
                return invoke(target, method, args);
            }
        };
        function.setParentScope(scope);
        function.setPrototype(ScriptableObject.getFunctionPrototype(scope));
        return function;
    }

    private Object invoke(Object target, Method method, Object[] args) {
        try {
            var converted = convertArguments(method, args);
            return wrap(method.invoke(target, converted));
        } catch (InvocationTargetException exception) {
            var cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw Context.reportRuntimeError(messageOf(cause));
        } catch (ReflectiveOperationException | IllegalArgumentException exception) {
            throw Context.reportRuntimeError(messageOf(exception));
        }
    }

    private Object[] convertArguments(Method method, Object[] args) {
        var types = method.getParameterTypes();
        if (args.length > types.length) {
            throw Context.reportRuntimeError(method.getName() + " received too many arguments");
        }
        var result = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            var value = index < args.length ? args[index] : Undefined.instance;
            result[index] = convert(value, types[index], method.getName());
        }
        return result;
    }

    private Object convert(Object value, Class<?> type, String name) {
        if (type == Object.class) {
            return value;
        }
        if (type == String.class) {
            if (value == Undefined.instance) {
                throw Context.reportRuntimeError(name + " requires a string");
            }
            return Context.toString(value);
        }
        if (type == int.class || type == Integer.class) {
            if (value == Undefined.instance) {
                throw Context.reportRuntimeError(name + " requires a 32-bit integer");
            }
            var number = Context.toNumber(value);
            if (!Double.isFinite(number) || number != Math.rint(number)
                    || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw Context.reportRuntimeError(name + " requires a 32-bit integer");
            }
            return (int) number;
        }
        if (type == long.class || type == Long.class) {
            if (value == Undefined.instance) {
                throw Context.reportRuntimeError(name + " requires a safe integer");
            }
            var number = Context.toNumber(value);
            if (!Double.isFinite(number) || number != Math.rint(number)
                    || Math.abs(number) > 9_007_199_254_740_991D) {
                throw Context.reportRuntimeError(name + " requires a safe integer");
            }
            return (long) number;
        }
        if (type == boolean.class || type == Boolean.class) {
            if (value == Undefined.instance) {
                throw Context.reportRuntimeError(name + " requires a boolean");
            }
            return Context.toBoolean(value);
        }
        if (Function.class.isAssignableFrom(type) && value instanceof Function function) {
            return function;
        }
        if (Scriptable.class.isAssignableFrom(type) && value instanceof Scriptable scriptable) {
            return scriptable;
        }
        var delegate = delegate(value);
        if (type.isInstance(delegate)) {
            return delegate;
        }
        throw Context.reportRuntimeError(name + " received an incompatible argument");
    }

    private static Collection<Method> exposedMethods(Class<?> type) {
        var methods = new ArrayList<Method>();
        for (var method : type.getDeclaredMethods()) {
            var modifiers = method.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)
                    || method.isSynthetic() || method.isBridge()) {
                continue;
            }
            method.trySetAccessible();
            methods.add(method);
        }
        return methods;
    }

    private static void requireBridge(Class<?> type) {
        if (!type.isAnnotationPresent(JsBridge.class)) {
            throw new IllegalArgumentException("Not a JavaScript bridge class: " + type.getName());
        }
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
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String messageOf(Throwable throwable) {
        return throwable == null || throwable.getMessage() == null
                ? "JavaScript bridge invocation failed"
                : throwable.getMessage();
    }

    /** Native JS object carrying its facade outside the visible property namespace. */
    private static final class BridgeObject extends NativeObject {
        private static final long serialVersionUID = 1L;
        private final Object delegate;

        private BridgeObject(Object delegate) {
            this.delegate = delegate;
        }
    }
}
