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
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Bounded conversion between detached Minecraft NBT and JavaScript value trees. */
final class NbtJs {
    static final int MAX_DEPTH = 24;
    static final int MAX_NODES = 4096;
    private static final long SAFE_LONG = 9_007_199_254_740_991L;

    private NbtJs() {
    }

    static Object toJs(Context context, Scriptable scope, Tag tag) {
        return convert(new Conversion(context, scope), tag, 0);
    }

    static CompoundTag fromObject(Context context, Scriptable object, String name) {
        return compoundFrom(new Conversion(context, null), object, name, 0);
    }

    private static Object convert(Conversion conversion, Tag tag, int depth) {
        conversion.visit(depth, "read");
        if (tag == null || tag instanceof EndTag) {
            return null;
        }
        if (tag instanceof CompoundTag compound) {
            var result = conversion.context.newObject(conversion.scope);
            for (var key : compound.getAllKeys()) {
                var value = convert(conversion, compound.get(key), depth + 1);
                if (value != null) {
                    ScriptableObject.defineProperty(
                            result, key, value,
                            ScriptableObject.READONLY | ScriptableObject.PERMANENT);
                }
            }
            return result;
        }
        if (tag instanceof CollectionTag<?> collection) {
            var values = new Object[collection.size()];
            for (int index = 0; index < collection.size(); index++) {
                values[index] = convert(conversion, collection.get(index), depth + 1);
            }
            return conversion.context.newArray(conversion.scope, values);
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
        conversion.visit(depth, "construction");
        var result = new CompoundTag();
        for (Object id : object.getIds()) {
            var key = Context.toString(id);
            var value = ScriptableObject.getProperty(object, key);
            if (value == Scriptable.NOT_FOUND || value == Undefined.instance || value == null) {
                continue;
            }
            result.put(key, toTag(conversion, value, name + "." + key, depth + 1));
        }
        return result;
    }

    private static Tag toTag(Conversion conversion, Object value, String path, int depth) {
        conversion.visit(depth, "construction");
        if (value instanceof NativeArray array) {
            var list = new net.minecraft.nbt.ListTag();
            for (long index = 0; index < array.getLength(); index++) {
                var element = array.get((int) index, array);
                if (element != Undefined.instance && element != null) {
                    if (!list.addTag(list.size(),
                            toTag(conversion, element, path + "[" + index + "]", depth + 1))) {
                        throw Context.reportRuntimeError(
                                "NBT list elements must have one type at " + path);
                    }
                }
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
            return ByteTag.valueOf(bool);
        }
        if (value instanceof Number number) {
            var doubleValue = number.doubleValue();
            if (!Double.isFinite(doubleValue)) {
                throw Context.reportRuntimeError("NBT numbers must be finite at " + path);
            }
            if (doubleValue == Math.rint(doubleValue)) {
                var longValue = number.longValue();
                return longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE
                        ? IntTag.valueOf((int) longValue)
                        : LongTag.valueOf(longValue);
            }
            return DoubleTag.valueOf(doubleValue);
        }
        throw Context.reportRuntimeError("Unsupported JavaScript value in NBT at " + path);
    }

    private static final class Conversion {
        private final Context context;
        private final Scriptable scope;
        private int nodes;

        private Conversion(Context context, Scriptable scope) {
            this.context = context;
            this.scope = scope;
        }

        private void visit(int depth, String operation) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
                throw Context.reportRuntimeError("NBT exceeds the script " + operation + " limit");
            }
        }
    }
}
