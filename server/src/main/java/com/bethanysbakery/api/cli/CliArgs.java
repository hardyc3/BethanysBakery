package com.bethanysbakery.api.cli;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CliArgs {
    private CliArgs() {}

    public static Map<String, String> parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) continue;
            String withoutPrefix = arg.substring(2);
            int equals = withoutPrefix.indexOf('=');
            if (equals >= 0) {
                values.put(withoutPrefix.substring(0, equals), withoutPrefix.substring(equals + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                values.put(withoutPrefix, args[++i]);
            } else {
                values.put(withoutPrefix, "true");
            }
        }
        return values;
    }
}
