package eu.example.ishare.apps.common;

import java.net.URI;
import java.util.Locale;

public final class AppConfig {
    public static String string(String key, String defaultValue) {
        String systemValue = System.getProperty(key);

        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        String envValue = System.getenv(toEnvKey(key));

        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return defaultValue;
    }

    public static int integer(String key, int defaultValue) {
        String value = string(key, Integer.toString(defaultValue));

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    public static URI uri(String key, String defaultValue) {
        return URI.create(string(key, defaultValue));
    }

    private static String toEnvKey(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
