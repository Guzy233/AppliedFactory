package com.fulent.appliedfactory.factory;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fulent.appliedfactory.script.ScriptContinuation;
import com.fulent.appliedfactory.script.ScriptExecutionContext;
import com.fulent.appliedfactory.script.ScriptHandlerRef;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * The program's initializer, run when the watched topology changes. Unlike other jobs it is
 * never persisted: after a chunk reload the program simply re-initializes. Finishing it marks
 * the program initialized; failing it leaves the program uninitialized until the topology
 * changes again.
 */
final class InitializerJob extends FactoryJob {
    InitializerJob(
            UUID id, FactoryProgram.Host host, Set<Direction> accessibleNetworks) {
        super(id, host, accessibleNetworks, List.of(), null,
                ScriptContinuation.empty(), null, 0);
    }

    @Override
    ScriptHandlerRef handlerRef() {
        return ScriptHandlerRef.initializer();
    }

    @Override
    ScriptExecutionContext createContext() {
        return context(null, List.of(), List.of());
    }

    @Override
    void saveParams(CompoundTag tag, HolderLookup.Provider registries) {
        throw new UnsupportedOperationException("Initializer jobs are never persisted");
    }
}
