package com.fulent.appliedfactory.script;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Bounded, type-safe conversion between vanilla NBT tags and read-only
 * JavaScript value trees. Objects are built from native Rhino objects so they
 * survive continuation serialization, and the conversion is capped in depth and
 * node count so hostile or accidental block/item NBT cannot exhaust the script
 * budget.
 */
public final class NbtJs {
    public static final int MAX_DEPTH = 24;
    public static final int MAX_NODES = 4096;
    private static final long SAFE_LONG = 1L << 53;

    private NbtJs() {
    }

    /** Converts one NBT tag into a JavaScript value tree (object/array/number/string). */
    public static Object toJs(Context cx, Scriptable scope, Tag tag) {
        return convert(new Conversion(cx, scope), tag, 0);
    }

    /**
     * Converts a plain JavaScript object into a CompoundTag. Numbers become int
     * or double tags, booleans become byte tags, strings stay strings, arrays
     * become list tags and nested objects become compound tags. Used for item
     * component patches supplied to {@code item(id, amount, nbt)}.
     */
    public static CompoundTag fromObject(Context cx, Scriptable object, String name) {
        return compoundFrom(new Conversion(cx, null), object, name, 0);
    }

    private static Object convert(Conversion conversion, Tag tag, int depth) {
        if (tag == null || tag instanceof EndTag) {
            return null;
        }
        if (depth > MAX_DEPTH || ++conversion.nodes > MAX_NODES) {
            throw Context.reportRuntimeError("NBT exceeds the script read limit");
        }
        if (tag instanceof CompoundTag compound) {
            var result = conversion.cx.newObject(conversion.scope);
            for (var key : compound.getAllKeys()) {
                var value = convert(conversion, compound.get(key), depth + 1);
                if (value != null) {
                    Jsify.defineReadOnly(result, key, value);
                }
            }
            return result;
        }
        if (tag instanceof CollectionTag<?> collection) {
            var values = new Object[collection.size()];
            var count = 0;
            for (int index = 0; index < collection.size(); index++) {
                var value = convert(conversion, collection.get(index), depth + 1);
                if (value != null) {
                    values[count++] = value;
                }
            }
            if (count != values.length) {
                var trimmed = new Object[count];
                System.arraycopy(values, 0, trimmed, 0, count);
                return conversion.cx.newArray(conversion.scope, trimmed);
            }
            return conversion.cx.newArray(conversion.scope, values);
        }
        if (tag instanceof StringTag string) {
            return string.getAsString();
        }
        if (tag instanceof LongTag longTag) {
            var value = longTag.getAsLong();
            return value >= -SAFE_LONG && value <= SAFE_LONG
                    ? (double) value
                    : String.valueOf(value);
        }
        if (tag instanceof NumericTag numeric) {
            return numeric.getAsDouble();
        }
        throw Context.reportRuntimeError("Unsupported NBT tag type in script read");
    }

    private static CompoundTag compoundFrom(
            Conversion conversion, Scriptable object, String name, int depth) {
        if (depth > MAX_DEPTH || ++conversion.nodes > MAX_NODES) {
            throw Context.reportRuntimeError("NBT exceeds the script construction limit");
        }
        var result = new CompoundTag();
        for (Object id : object.getIds()) {
            var key = Context.toString(id);
            var value = ScriptableObject.getProperty(object, key);
            if (value == Scriptable.NOT_FOUND || value == Undefined.instance || value == null) {
                continue;
            }
            result.put(key, toTag(conversion, value, name + "." + key, depth));
        }
        return result;
    }

    private static Tag toTag(Conversion conversion, Object value, String path, int depth) {
        if (depth > MAX_DEPTH || ++conversion.nodes > MAX_NODES) {
            throw Context.reportRuntimeError("NBT exceeds the script construction limit");
        }
        if (value instanceof NativeArray array) {
            var list = new ListTag();
            for (long index = 0; index < array.getLength(); index++) {
                var element = array.get((int) index, array);
                if (element == Undefined.instance || element == null) {
                    continue;
                }
                list.add(toTag(conversion, element, path + "[" + index + "]", depth + 1));
            }
            return list;
        }
        if (value instanceof Scriptable scriptable) {
            if (scriptable instanceof Function) {
                throw Context.reportRuntimeError("Functions are not valid NBT at " + path);
            }
            return compoundFrom(conversion, scriptable, path, depth + 1);
        }
        if (value instanceof String string) {
            return StringTag.valueOf(string);
        }
        if (value instanceof Boolean bool) {
            return ByteTag.valueOf(bool ? (byte) 1 : (byte) 0);
        }
        if (value instanceof Number number) {
            var doubleValue = number.doubleValue();
            if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
                throw Context.reportRuntimeError("NBT numbers must be finite at " + path);
            }
            if (doubleValue == Math.rint(doubleValue)) {
                var longValue = number.longValue();
                if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                    return IntTag.valueOf((int) longValue);
                }
                return LongTag.valueOf(longValue);
            }
            return DoubleTag.valueOf(doubleValue);
        }
        throw Context.reportRuntimeError("Unsupported JavaScript value in NBT at " + path);
    }

    private static final class Conversion {
        private final Context cx;
        private final Scriptable scope;
        private int nodes;

        private Conversion(Context cx, Scriptable scope) {
            this.cx = cx;
            this.scope = scope;
        }
    }
}
