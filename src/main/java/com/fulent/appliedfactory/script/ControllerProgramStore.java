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
 * World-level editable/executable source storage for factory controllers.
 *
 * <p>Chunk NBT only keeps a UUID. Sources are stored as UTF-8 bytes rather than NBT strings:
 * NBT strings have a much smaller practical size ceiling than the 128k-character program limit.
 */
public final class ControllerProgramStore extends SavedData {
    private static final String DATA_FILE = "appliedfactory_controller_programs";
    private static final String PROGRAMS_NBT_KEY = "Programs";
    private static final String ID_NBT_KEY = "Id";
    private static final String SOURCE_NBT_KEY = "Source";
    private static final String COMPILED_SOURCE_NBT_KEY = "CompiledSource";
    private static final String WORKSPACE_PATH_NBT_KEY = "WorkspacePath";
    private static final String UPDATED_AT_NBT_KEY = "UpdatedAt";
    private static final SavedData.Factory<ControllerProgramStore> FACTORY = new SavedData.Factory<>(
            ControllerProgramStore::new, ControllerProgramStore::load);

    private final Map<UUID, StoredProgram> programs = new HashMap<>();

    public static ControllerProgramStore get(ServerLevel level) {
        // The overworld data directory is shared by controllers in every dimension.
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_FILE);
    }

    public Optional<ControllerProgramSources> get(UUID id) {
        var stored = programs.get(id);
        if (stored == null
                || stored.source().length > ControllerProgram.MAX_SOURCE_BYTES
                || stored.compiledSource().length > ControllerProgram.MAX_SOURCE_BYTES) {
            return Optional.empty();
        }
        var source = new String(stored.source(), StandardCharsets.UTF_8);
        var compiledSource = new String(stored.compiledSource(), StandardCharsets.UTF_8);
        if (!ControllerProgram.isWithinLimit(source)
                || !ControllerProgram.isWithinLimit(compiledSource)) {
            return Optional.empty();
        }
        return Optional.of(new ControllerProgramSources(
                source, compiledSource, stored.workspacePath(), stored.updatedAt()));
    }

    public void put(UUID id, ControllerProgramSources program) {
        if (!ControllerProgram.isWithinLimit(program.source())
                || !ControllerProgram.isWithinLimit(program.compiledSource())) {
            throw new IllegalArgumentException("Controller program exceeds the source limit");
        }
        if (!program.workspacePath().isEmpty()
                && !ControllerProgram.isWorkspacePathWithinLimit(program.workspacePath())) {
            throw new IllegalArgumentException("Controller workspace path is invalid");
        }
        var stored = new StoredProgram(
                program.source().getBytes(StandardCharsets.UTF_8),
                program.compiledSource().getBytes(StandardCharsets.UTF_8),
                program.workspacePath(), program.updatedAt());
        var previous = programs.put(id, stored);
        if (!stored.contentEquals(previous)) {
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
        programs.forEach((id, program) -> {
            var entry = new CompoundTag();
            entry.putUUID(ID_NBT_KEY, id);
            entry.putByteArray(SOURCE_NBT_KEY, program.source());
            entry.putByteArray(COMPILED_SOURCE_NBT_KEY, program.compiledSource());
            entry.putString(WORKSPACE_PATH_NBT_KEY, program.workspacePath());
            entry.putLong(UPDATED_AT_NBT_KEY, program.updatedAt());
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
            var compiled = entry.contains(COMPILED_SOURCE_NBT_KEY, Tag.TAG_BYTE_ARRAY)
                    ? entry.getByteArray(COMPILED_SOURCE_NBT_KEY) : source;
            var path = entry.contains(WORKSPACE_PATH_NBT_KEY, Tag.TAG_STRING)
                    ? entry.getString(WORKSPACE_PATH_NBT_KEY) : "";
            var updatedAt = entry.contains(UPDATED_AT_NBT_KEY, Tag.TAG_LONG)
                    ? Math.max(0L, entry.getLong(UPDATED_AT_NBT_KEY)) : 0L;
            if (source.length <= ControllerProgram.MAX_SOURCE_BYTES
                    && compiled.length <= ControllerProgram.MAX_SOURCE_BYTES
                    && (path.isEmpty() || ControllerProgram.isWorkspacePathWithinLimit(path))) {
                result.programs.put(entry.getUUID(ID_NBT_KEY),
                        new StoredProgram(source, compiled, path, updatedAt));
            }
        }
        return result;
    }

    private record StoredProgram(
            byte[] source, byte[] compiledSource, String workspacePath, long updatedAt) {
        private boolean contentEquals(StoredProgram other) {
            return other != null
                    && Arrays.equals(source, other.source)
                    && Arrays.equals(compiledSource, other.compiledSource)
                    && workspacePath.equals(other.workspacePath)
                    && updatedAt == other.updatedAt;
        }
    }
}
