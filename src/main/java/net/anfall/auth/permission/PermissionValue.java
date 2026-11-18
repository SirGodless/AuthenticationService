package net.anfall.auth.permission;

/**
 * Wert eines Rechts bei einem User oder in einer Quelle (Rolle, direkt).
 * "Nicht gesetzt" ist ein eigener Zustand und nie gleich 0. "Unbegrenzt" ist ebenfalls ein eigener Zustand.
 * Bei booleschen Rechten bedeutet "gesetzt" einfach "vergeben".
 */
public record PermissionValue(boolean set, Long number, boolean unlimited) {

    public static final PermissionValue NOT_SET = new PermissionValue(false, null, false);
    public static final PermissionValue GRANTED = new PermissionValue(true, null, false);
    public static final PermissionValue UNLIMITED = new PermissionValue(true, null, true);

    public static PermissionValue ofNumber(long value) {
        return new PermissionValue(true, value, false);
    }
}
