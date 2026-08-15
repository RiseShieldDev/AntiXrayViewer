package com.example.antixrayviewer.replay;

/**
 * Режимы камеры во время воспроизведения.
 */
public enum CameraMode {

    /** От первого лица: плавно следуем за глазами игрока с его углами обзора. */
    FIRST_PERSON("От первого лица"),

    /** От третьего лица: камера позади игрока, с учётом стен. */
    THIRD_PERSON("От третьего лица"),

    /** Свободный полёт: плагин не двигает зрителя вообще. */
    FREE_LOOK("Свободная камера");

    private final String displayName;

    CameraMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public CameraMode next() {
        CameraMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static CameraMode parse(String raw, CameraMode fallback) {
        if (raw == null) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        switch (normalized) {
            case "FIRST":
            case "FIRST_PERSON":
            case "FP":
                return FIRST_PERSON;
            case "THIRD":
            case "THIRD_PERSON":
            case "TP":
                return THIRD_PERSON;
            case "FREE":
            case "FREE_LOOK":
            case "FREECAM":
            // Старые значения из конфига больше не валят старт, а сводятся к ближайшему режиму
            case "ATTACHED":
            case "LOCK":
            case "LOCKED":
                return FREE_LOOK;
            default:
                return fallback;
        }
    }
}
