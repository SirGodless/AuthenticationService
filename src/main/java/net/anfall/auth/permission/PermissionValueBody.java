package net.anfall.auth.permission;

/**
 * Wert eines Rechts im Request. Boolesches Recht: leer lassen. Zahlenrecht: entweder "number" oder "unlimited": true.
 * Ob das zum Recht passt, prüft {@link PermissionService#validate}.
 */
public record PermissionValueBody(Long number, Boolean unlimited) {

    public PermissionValue toValue() {
        return new PermissionValue(true, number, Boolean.TRUE.equals(unlimited));
    }
}
