package net.anfall.auth.permission;

/**
 * Ein Recht und seine Eigenschaften. type ist "boolean" oder "number". Zahlenrechte haben eine Richtung:
 * "higher" (höher = mehr Rechte) oder "lower" (niedriger = mehr Rechte). "Unbegrenzt" gibt es nur bei "higher".
 */
public record PermissionDefinition(String key, String type, String direction, boolean allowUnlimited,
                                   boolean linkGrantable, boolean builtin, String description) {

    public boolean isNumber() {
        return "number".equals(type);
    }
}
