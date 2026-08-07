package com.fulent.appliedfactory.factory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.script.FactoryScriptAction;
import com.fulent.appliedfactory.script.ScriptContinuation;
import com.fulent.appliedfactory.script.ScriptExecutionContext;
import com.fulent.appliedfactory.script.ScriptHandlerRef;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * A job running one registered passive handler. Passive jobs restart automatically after
 * finishing unless the program records the index as stopped.
 */
final class PassiveJob extends FactoryJob {
    static final String PASSIVE_INDEX_TAG = "PassiveIndex";

    private final int passiveIndex;

    PassiveJob(
            UUID id,
            FactoryProgram.Host host,
            Set<Direction> accessibleNetworks,
            int passiveIndex,
            List<FactoryResource> owned,
            ScriptContinuation continuation,
            @Nullable FactoryScriptAction action,
            long actionStartedTick) {
        super(id, host, accessibleNetworks, owned, null,
                continuation, action, actionStartedTick);
        this.passiveIndex = passiveIndex;
    }

    int passiveIndex() {
        return passiveIndex;
    }

    @Override
    ScriptHandlerRef handlerRef() {
        return ScriptHandlerRef.passive(passiveIndex);
    }

    @Override
    ScriptExecutionContext createContext() {
        return context(null, List.of(), List.of());
    }

    @Override
    void saveParams(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(PASSIVE_INDEX_TAG, passiveIndex);
    }

    static Optional<FactoryJob> loadParams(
            CompoundTag tag,
            FactoryProgram.Host host,
            HolderLookup.Provider registries,
            List<FactoryResource> owned,
            @Nullable Direction recoverySide,
            FactoryScriptAction action,
            byte[] continuation,
            long actionStartedTick) {
        if (!tag.contains(PASSIVE_INDEX_TAG, Tag.TAG_INT)) {
            return Optional.empty();
        }
        var passiveIndex = tag.getInt(PASSIVE_INDEX_TAG);
        if (passiveIndex < 0) {
            return Optional.empty();
        }
        return Optional.of(new PassiveJob(
                tag.getUUID(ID_TAG),
                host,
                java.util.EnumSet.allOf(Direction.class),
                passiveIndex,
                owned,
                ScriptContinuation.ofPersisted(continuation),
                action,
                actionStartedTick));
    }
}
