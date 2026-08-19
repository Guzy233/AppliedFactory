package com.fulent.appliedfactory.script;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

/** Allow-list binder exposing dedicated facade objects through polyglot proxies. */
final class JsBridgeBinder {
    static final Object UNDEFINED = new Object();

    private final Context context;
    private final Value bindings;
    private final Value plainArrayFactory;
    private final Value arrayFactory;

    JsBridgeBinder(Context context) {
        this.context = context;
        bindings = context.getBindings("js");
        plainArrayFactory = context.eval("js", "values => Object.freeze(Array.from(values))");
        arrayFactory = context.eval("js", """
                (values, methods) => {
                  const array = Array.from(values);
                  for (const name of Object.keys(methods)) {
                    Object.defineProperty(array, name, {
                      value: methods[name], enumerable: false, writable: false, configurable: false
                    });
                  }
                  return Object.freeze(array);
                }
                """);
    }

    void installGlobals(Object facade) {
        requireBridge(facade.getClass());
        for (var method : exposedMethods(facade.getClass())) {
            if (method.getAnnotation(JsProperty.class) != null) {
                throw new IllegalStateException("Global API cannot expose properties: " + method);
            }
            bindings.putMember(jsName(method), function(facade, method));
        }
    }

    void installGlobal(String name, Object value) {
        bindings.putMember(name, wrap(value));
    }

    Object wrap(Object value) {
        if (value == UNDEFINED) {
            return context.eval("js", "undefined");
        }
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Value
                || value instanceof ProxyObject || value instanceof ProxyArray
                || value instanceof ProxyExecutable) {
            return value;
        }
        if (value instanceof Collection<?> collection) {
            return plainArrayFactory.execute(
                    ProxyArray.fromArray(collection.stream().map(this::wrap).toArray()));
        }
        if (value instanceof Map<?, ?> map) {
            var converted = new LinkedHashMap<String, Object>();
            map.forEach((key, entryValue) -> converted.put(String.valueOf(key), wrap(entryValue)));
            return new MapObject(converted);
        }
        if (value.getClass().isArray()) {
            var length = java.lang.reflect.Array.getLength(value);
            var converted = new Object[length];
            for (int index = 0; index < length; index++) {
                converted[index] = wrap(java.lang.reflect.Array.get(value, index));
            }
            return plainArrayFactory.execute(ProxyArray.fromArray(converted));
        }
        requireBridge(value.getClass());
        return new BridgeObject(value);
    }

    Value arrayWithMethods(Object[] values, Object methods) {
        return arrayFactory.execute(ProxyArray.fromArray(values), wrap(methods));
    }

    Object delegate(Object value) {
        if (value instanceof BridgeObject bridge) {
            return bridge.delegate;
        }
        if (value instanceof Value guest && guest.isProxyObject()) {
            var proxy = guest.asProxyObject();
            return proxy instanceof BridgeObject bridge ? bridge.delegate : null;
        }
        return null;
    }

    private ProxyExecutable function(Object target, Method method) {
        return arguments -> invoke(target, method, arguments);
    }

    private Object invoke(Object target, Method method, Value[] arguments) {
        try {
            return wrap(method.invoke(target, convertArguments(method, arguments)));
        } catch (InvocationTargetException exception) {
            var cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw JsValues.error(messageOf(cause));
        } catch (ReflectiveOperationException | IllegalArgumentException exception) {
            throw JsValues.error(messageOf(exception));
        }
    }

    private Object[] convertArguments(Method method, Value[] arguments) {
        var types = method.getParameterTypes();
        if (arguments.length > types.length) {
            throw JsValues.error(method.getName() + " received too many arguments");
        }
        var result = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            var value = index < arguments.length ? arguments[index] : null;
            result[index] = convert(value, types[index], method.getName());
        }
        return result;
    }

    private Object convert(Value value, Class<?> type, String name) {
        if (value == null) {
            if (type == Object.class) {
                return UNDEFINED;
            }
            throw JsValues.error(name + " is missing a required argument");
        }
        if (type == Object.class) {
            return JsValues.toHost(value);
        }
        if (type == String.class) {
            return JsValues.string(value, name);
        }
        if (type == int.class || type == Integer.class) {
            var number = JsValues.number(value, name);
            if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw JsValues.error(name + " requires a 32-bit integer");
            }
            return (int) number;
        }
        if (type == long.class || type == Long.class) {
            var number = JsValues.number(value, name);
            if (number != Math.rint(number) || Math.abs(number) > 9_007_199_254_740_991D) {
                throw JsValues.error(name + " requires a safe integer");
            }
            return (long) number;
        }
        if (type == boolean.class || type == Boolean.class) {
            if (!value.isBoolean()) {
                throw JsValues.error(name + " requires a boolean");
            }
            return value.asBoolean();
        }
        if (type == Value.class && value.canExecute()) {
            return value;
        }
        var delegate = delegate(value);
        if (type.isInstance(delegate)) {
            return delegate;
        }
        throw JsValues.error(name + " received an incompatible argument");
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

    private static String jsName(Method method) {
        var annotation = method.getAnnotation(JsName.class);
        return annotation == null ? method.getName() : annotation.value();
    }

    private static String propertyName(Method method) {
        var annotation = method.getAnnotation(JsProperty.class);
        if (annotation != null && !annotation.value().isBlank()) {
            return annotation.value();
        }
        var name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            name = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2) {
            name = name.substring(2);
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static String messageOf(Throwable throwable) {
        return throwable == null || throwable.getMessage() == null
                ? "JavaScript bridge invocation failed" : throwable.getMessage();
    }

    private final class BridgeObject implements ProxyObject {
        private final Object delegate;
        private final Map<String, Method> methods = new LinkedHashMap<>();
        private final Map<String, Method> properties = new LinkedHashMap<>();

        private BridgeObject(Object delegate) {
            this.delegate = delegate;
            for (var method : exposedMethods(delegate.getClass())) {
                if (method.getAnnotation(JsProperty.class) == null) {
                    methods.put(jsName(method), method);
                } else {
                    properties.put(propertyName(method), method);
                }
            }
        }

        @Override
        public Object getMember(String key) {
            var property = properties.get(key);
            if (property != null) {
                return invoke(delegate, property, new Value[0]);
            }
            var method = methods.get(key);
            return method == null ? null : function(delegate, method);
        }

        @Override
        public Object getMemberKeys() {
            var keys = new ArrayList<String>(properties.keySet());
            keys.addAll(methods.keySet());
            return ProxyArray.fromArray(keys.toArray());
        }

        @Override
        public boolean hasMember(String key) {
            return properties.containsKey(key) || methods.containsKey(key);
        }

        @Override
        public void putMember(String key, Value value) {
            throw JsValues.error(key + " is read-only");
        }
    }

    /** Read-only map proxy whose member-key list remains accessible under HostAccess.NONE. */
    private static final class MapObject implements ProxyObject {
        private final Map<String, Object> values;

        private MapObject(Map<String, Object> values) {
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        @Override
        public Object getMember(String key) {
            return values.get(key);
        }

        @Override
        public Object getMemberKeys() {
            return ProxyArray.fromArray(values.keySet().toArray());
        }

        @Override
        public boolean hasMember(String key) {
            return values.containsKey(key);
        }

        @Override
        public void putMember(String key, Value value) {
            throw JsValues.error(key + " is read-only");
        }
    }
}
