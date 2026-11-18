package net.anfall.auth.invite;

import net.anfall.auth.permission.PermissionValue;

/** Ein Recht mit Wert, wie es ein Link vergibt. Boolesch: number null und unlimited false. */
public record Grant(String key, Long number, boolean unlimited) {

    public PermissionValue toValue() {
        return new PermissionValue(true, number, unlimited);
    }

    public static Grant of(String key, PermissionValue v) {
        return new Grant(key, v.number(), v.unlimited());
    }
}
