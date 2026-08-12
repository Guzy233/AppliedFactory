package com.fulent.appliedfactory.script;

/** Limits and persistent field names for the source owned by one controller. */
public final class ControllerProgram {
    public static final int MAX_SOURCE_LENGTH = 32_768;
    public static final String NBT_KEY = "ControllerProgram";
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
}
