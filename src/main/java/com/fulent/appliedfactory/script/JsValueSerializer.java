package com.fulent.appliedfactory.script;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.graalvm.polyglot.Value;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Best-effort bounded conversion of a GraalJS value for MCP probe results. */
public final class JsValueSerializer {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 16_384;
    private final Set<Value> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
    private int nodes;

    private JsValueSerializer() {
    }

    @Nullable
    public static JsonElement serialize(Value value) {
        try {
            return new JsValueSerializer().serializeValue(value, 0);
        } catch (RuntimeException exception) {
            return JsonNull.INSTANCE;
        }
    }

    private JsonElement serializeValue(Value value, int depth) {
        if (value == null || value.isNull()) {
            return JsonNull.INSTANCE;
        }
        if (value.isString()) {
            return new JsonPrimitive(value.asString());
        }
        if (value.isBoolean()) {
            return new JsonPrimitive(value.asBoolean());
        }
        if (value.isNumber()) {
            var number = value.asDouble();
            return Double.isFinite(number)
                    ? new JsonPrimitive(number) : new JsonPrimitive(String.valueOf(number));
        }
        if (value.canExecute()) {
            return new JsonPrimitive("[function]");
        }
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
            return new JsonPrimitive("...");
        }
        if (!visiting.add(value)) {
            return new JsonPrimitive("...[cycle]");
        }
        try {
            if (value.hasArrayElements()) {
                var result = new com.google.gson.JsonArray();
                for (long index = 0; index < value.getArraySize() && nodes < MAX_NODES; index++) {
                    result.add(serializeValue(value.getArrayElement(index), depth + 1));
                }
                return result;
            }
            if (value.hasMembers()) {
                var result = new JsonObject();
                for (var name : value.getMemberKeys()) {
                    if (nodes >= MAX_NODES) {
                        break;
                    }
                    try {
                        var property = value.getMember(name);
                        if (property != null && !property.canExecute()) {
                            result.add(name, serializeValue(property, depth + 1));
                        }
                    } catch (RuntimeException ignored) {
                        // A throwing getter does not make the whole probe result unusable.
                    }
                }
                return result;
            }
            return new JsonPrimitive(value.toString());
        } finally {
            visiting.remove(value);
        }
    }
}
