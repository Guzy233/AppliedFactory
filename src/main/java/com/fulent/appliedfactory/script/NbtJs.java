package com.fulent.appliedfactory.script;

import java.util.LinkedHashMap;
import java.util.ArrayList;

import org.graalvm.polyglot.Value;

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

    static Object toJs(Tag tag) {
        return convert(new Conversion(), tag, 0);
    }

    static CompoundTag fromObject(Value object, String name) {
        if (!object.hasMembers() || object.hasArrayElements() || object.canExecute()) {
            throw JsValues.error(name + " must be an NBT object");
        }
        return compoundFrom(new Conversion(), object, name, 0);
    }

    private static Object convert(Conversion conversion, Tag tag, int depth) {
        conversion.visit(depth, "read");
        if (tag == null || tag instanceof EndTag) {
            return null;
        }
        if (tag instanceof CompoundTag compound) {
            var result = new LinkedHashMap<String, Object>();
            for (var key : compound.getAllKeys()) {
                var value = convert(conversion, compound.get(key), depth + 1);
                if (value != null) {
                    result.put(key, value);
                }
            }
            return result;
        }
        if (tag instanceof CollectionTag<?> collection) {
            var values = new ArrayList<Object>(collection.size());
            for (int index = 0; index < collection.size(); index++) {
                values.add(convert(conversion, collection.get(index), depth + 1));
            }
            return values;
        }
        if (tag instanceof StringTag string) {
            return string.getAsString();
        }
        if (tag instanceof LongTag longTag) {
            var value = longTag.getAsLong();
            return value >= -SAFE_LONG && value <= SAFE_LONG ? (double) value : String.valueOf(value);
        }
        if (tag instanceof NumericTag numeric) {
            return numeric.getAsDouble();
        }
        throw JsValues.error("Unsupported NBT tag type in script read");
    }

    private static CompoundTag compoundFrom(
            Conversion conversion, Value object, String name, int depth) {
        conversion.visit(depth, "construction");
        var result = new CompoundTag();
        for (var key : object.getMemberKeys()) {
            var value = object.getMember(key);
            if (value != null && !value.isNull()) {
                result.put(key, toTag(conversion, value, name + "." + key, depth + 1));
            }
        }
        return result;
    }

    private static Tag toTag(Conversion conversion, Value value, String path, int depth) {
        conversion.visit(depth, "construction");
        if (value.hasArrayElements()) {
            var list = new net.minecraft.nbt.ListTag();
            for (long index = 0; index < value.getArraySize(); index++) {
                var element = value.getArrayElement(index);
                if (!element.isNull() && !list.addTag(list.size(),
                        toTag(conversion, element, path + "[" + index + "]", depth + 1))) {
                    throw JsValues.error("NBT list elements must have one type at " + path);
                }
            }
            return list;
        }
        if (value.canExecute()) {
            throw JsValues.error("Functions are not valid NBT at " + path);
        }
        if (value.hasMembers()) {
            return compoundFrom(conversion, value, path, depth + 1);
        }
        if (value.isString()) {
            return StringTag.valueOf(value.asString());
        }
        if (value.isBoolean()) {
            return ByteTag.valueOf(value.asBoolean());
        }
        if (value.isNumber()) {
            var number = value.asDouble();
            if (!Double.isFinite(number)) {
                throw JsValues.error("NBT numbers must be finite at " + path);
            }
            if (number == Math.rint(number)) {
                var integer = (long) number;
                return integer >= Integer.MIN_VALUE && integer <= Integer.MAX_VALUE
                        ? IntTag.valueOf((int) integer) : LongTag.valueOf(integer);
            }
            return DoubleTag.valueOf(number);
        }
        throw JsValues.error("Unsupported JavaScript value in NBT at " + path);
    }

    private static final class Conversion {
        private int nodes;

        private void visit(int depth, String operation) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
                throw JsValues.error("NBT exceeds the script " + operation + " limit");
            }
        }
    }
}
