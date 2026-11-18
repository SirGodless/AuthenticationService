package net.anfall.auth.permission;

import java.util.List;

/**
 * Berechnet das wirksame Recht aus allen Quellen (Rollen und direkte Rechte). Immer komplett neu, nie per Delta
 * (CLAUDE.md Abschnitt 5). Reine Rechenlogik ohne Datenbank, damit sie sich mit Konfliktfällen testen lässt.
 *
 * Regeln:
 * - Boolesch: Das Recht hat, wer es aus mindestens einer Quelle bekommt.
 * - Zahl: Es gilt der Wert, der mehr Rechte gibt. Richtung "higher": der größte, "lower": der kleinste.
 * - "Unbegrenzt" gewinnt gegen jede Zahl, gibt es aber nur bei Richtung "higher" (sonst wird es ignoriert).
 * - Eine Quelle, die den Wert nicht setzt, beeinflusst den Vergleich nicht. Setzt keine Quelle ihn, ist er nicht gesetzt
 *   (und das ist nicht 0).
 */
public final class EffectivePermissionCalculator {

    private EffectivePermissionCalculator() {
    }

    /**
     * @param sources die Werte aus allen Quellen für genau dieses Recht (nicht gesetzte Quellen weglassen oder NOT_SET)
     * @param implicitGrant true, wenn eine Rolle das Recht ohne Eintrag enthält (Rolle Admin für alle admin.*-Rechte)
     */
    public static PermissionValue combine(PermissionDefinition definition, List<PermissionValue> sources,
                                          boolean implicitGrant) {
        List<PermissionValue> set = sources.stream().filter(PermissionValue::set).toList();
        if (!definition.isNumber()) {
            return (implicitGrant || !set.isEmpty()) ? PermissionValue.GRANTED : PermissionValue.NOT_SET;
        }
        boolean unlimitedAllowed = definition.allowUnlimited() && "higher".equals(definition.direction());
        if (unlimitedAllowed && set.stream().anyMatch(PermissionValue::unlimited)) {
            return PermissionValue.UNLIMITED;
        }
        var numbers = set.stream().filter(v -> v.number() != null).mapToLong(PermissionValue::number);
        var best = "lower".equals(definition.direction()) ? numbers.min() : numbers.max();
        return best.isPresent() ? PermissionValue.ofNumber(best.getAsLong()) : PermissionValue.NOT_SET;
    }
}
