package com.fulent.appliedfactory.script;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Best-effort conversion of JavaScript values into Gson {@link JsonElement} for MCP tool
 * results. Reads facade properties (so {@code network('south').extract()} dumps work) with
 * depth, node and cycle guards.
 */
public final class JsValueSerializer {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 16_384;

    private final Set<Scriptable> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
    private int nodes;

    private JsValueSerializer() {
    }

    @Nullable
    public static JsonElement serialize(Context context, Scriptable scope, Object value) {
        try {
            return new JsValueSerializer().toJson(value);
        } catch (RuntimeException exception) {
            return JsonNull.INSTANCE;
        }
    }

    @Nullable
    private JsonElement toJson(Object value) {
        return serializeValue(value, 0);
    }

    @Nullable
    private JsonElement serializeValue(Object value, int depth) {
        if (value == null || value == Undefined.instance || value == Scriptable.NOT_FOUND) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof String string) {
            return new JsonPrimitive(string);
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof Double number) {
            return Double.isFinite(number) ? new JsonPrimitive(number) : new JsonPrimitive(String.valueOf(number));
        }
        if (value instanceof Long number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof Integer number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof Number number) {
            var asDouble = number.doubleValue();
            return Double.isFinite(asDouble)
                    ? new JsonPrimitive(asDouble)
                    : new JsonPrimitive(String.valueOf(asDouble));
        }
        if (!(value instanceof Scriptable scriptable)) {
            return new JsonPrimitive(String.valueOf(value));
        }
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
            return new JsonPrimitive("...");
        }
        if (scriptable instanceof Function) {
            return new JsonPrimitive("[function]");
        }
        if (!visiting.add(scriptable)) {
            return new JsonPrimitive("...[cycle]");
        }
        try {
            if (scriptable instanceof NativeArray array) {
                return serializeArray(array, depth);
            }
            return serializeObject(scriptable, depth);
        } finally {
            visiting.remove(scriptable);
        }
    }

    private JsonElement serializeArray(NativeArray array, int depth) {
        var result = new com.google.gson.JsonArray();
        var length = array.getLength();
        for (long index = 0; index < length && nodes < MAX_NODES; index++) {
            result.add(serializeValue(array.get((int) index, array), depth + 1));
        }
        return result;
    }

    private JsonElement serializeObject(Scriptable object, int depth) {
        var result = new JsonObject();
        for (var id : object.getIds()) {
            if (nodes >= MAX_NODES) {
                break;
            }
            var name = String.valueOf(id);
            Object property;
            try {
                property = ScriptableObject.getProperty(object, name);
            } catch (RuntimeException exception) {
                continue;
            }
            if (property == Scriptable.NOT_FOUND || property == Undefined.instance) {
                continue;
            }
            if (property instanceof Function) {
                continue;
            }
            var serialized = serializeValue(property, depth + 1);
            if (serialized != null) {
                result.add(name, serialized);
            }
        }
        return result;
    }
}
