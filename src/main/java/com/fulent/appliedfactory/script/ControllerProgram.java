package com.fulent.appliedfactory.script;

import java.nio.charset.StandardCharsets;

/** Limits and persistent field names for the source owned by one controller. */
public final class ControllerProgram {
    /**
     * Source is deliberately kept out of chunk NBT. Besides avoiding oversized chunk packets,
     * this allows a controller program to be large enough for generated recipe tables.
     */
    public static final int MAX_SOURCE_LENGTH = 131_072;
    /** A UTF-8 code point can occupy at most four bytes on the wire or in SavedData. */
    public static final int MAX_SOURCE_BYTES = MAX_SOURCE_LENGTH * 4;
    public static final int MAX_WORKSPACE_PATH_BYTES = 1_024;
    /** Legacy chunk-NBT field, read only so existing worlds can be migrated. */
    public static final String NBT_KEY = "ControllerProgram";
    /** Compact chunk-NBT reference to the source stored in ControllerProgramStore. */
    public static final String PROGRAM_ID_NBT_KEY = "ControllerProgramId";
    public static final String DEFAULT_SOURCE = """
            // Applied Factory MVP: register patterns and generator workflows here.
            go(function* () {
              while (true) {
                yield sleep(20);
              }
            });
            """;

    private ControllerProgram() {
    }

    public static boolean isWithinLimit(String source) {
        return source.length() <= MAX_SOURCE_LENGTH
                && source.getBytes(StandardCharsets.UTF_8).length <= MAX_SOURCE_BYTES;
    }

    public static boolean isWorkspacePathWithinLimit(String path) {
        return path != null && !path.isBlank()
                && !path.startsWith("/")
                && !path.contains("\\")
                && path.indexOf(':') < 0
                && java.util.Arrays.stream(path.split("/", -1))
                        .noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))
                && path.getBytes(StandardCharsets.UTF_8).length <= MAX_WORKSPACE_PATH_BYTES;
    }
}
