package com.fulent.appliedfactory.script;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-level source storage for factory controllers.
 *
 * <p>Chunk NBT only keeps a UUID. Source is stored as UTF-8 bytes rather than an NBT string:
 * NBT strings have a much smaller practical size ceiling than the 128k-character program limit.
 */
public final class ControllerProgramStore extends SavedData {
    private static final String DATA_FILE = "appliedfactory_controller_programs";
    private static final String PROGRAMS_NBT_KEY = "Programs";
    private static final String ID_NBT_KEY = "Id";
    private static final String SOURCE_NBT_KEY = "Source";
    private static final SavedData.Factory<ControllerProgramStore> FACTORY = new SavedData.Factory<>(
            ControllerProgramStore::new, ControllerProgramStore::load);

    private final Map<UUID, byte[]> programs = new HashMap<>();

    public static ControllerProgramStore get(ServerLevel level) {
        // The overworld data directory is shared by controllers in every dimension.
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_FILE);
    }

    public Optional<String> get(UUID id) {
        var bytes = programs.get(id);
        if (bytes == null || bytes.length > ControllerProgram.MAX_SOURCE_BYTES) {
            return Optional.empty();
        }
        var source = new String(bytes, StandardCharsets.UTF_8);
        return ControllerProgram.isWithinLimit(source) ? Optional.of(source) : Optional.empty();
    }

    public void put(UUID id, String source) {
        if (!ControllerProgram.isWithinLimit(source)) {
            throw new IllegalArgumentException("Controller program exceeds the source limit");
        }
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var previous = programs.put(id, bytes);
        if (!Arrays.equals(previous, bytes)) {
            setDirty();
        }
    }

    public void remove(UUID id) {
        if (programs.remove(id) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var savedPrograms = new ListTag();
        programs.forEach((id, source) -> {
            var entry = new CompoundTag();
            entry.putUUID(ID_NBT_KEY, id);
            entry.putByteArray(SOURCE_NBT_KEY, source);
            savedPrograms.add(entry);
        });
        tag.put(PROGRAMS_NBT_KEY, savedPrograms);
        return tag;
    }

    private static ControllerProgramStore load(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new ControllerProgramStore();
        var savedPrograms = tag.getList(PROGRAMS_NBT_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < savedPrograms.size(); index++) {
            var entry = savedPrograms.getCompound(index);
            if (!entry.hasUUID(ID_NBT_KEY) || !entry.contains(SOURCE_NBT_KEY, Tag.TAG_BYTE_ARRAY)) {
                continue;
            }
            var source = entry.getByteArray(SOURCE_NBT_KEY);
            if (source.length <= ControllerProgram.MAX_SOURCE_BYTES) {
                result.programs.put(entry.getUUID(ID_NBT_KEY), source);
            }
        }
        return result;
    }
}
