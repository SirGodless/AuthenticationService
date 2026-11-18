package net.anfall.auth.permission;

import static net.anfall.auth.permission.PermissionValue.GRANTED;
import static net.anfall.auth.permission.PermissionValue.NOT_SET;
import static net.anfall.auth.permission.PermissionValue.UNLIMITED;
import static net.anfall.auth.permission.PermissionValue.ofNumber;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Konfliktfälle aus CLAUDE.md Abschnitt 5: mehrere Quellen, gegensätzliche Richtung, nicht gesetzt, unbegrenzt. */
class EffectivePermissionCalculatorTest {

    private static final PermissionDefinition FLAG = new PermissionDefinition("x.flag", "boolean", null, false, false, false, "");
    private static final PermissionDefinition HIGHER = new PermissionDefinition("x.higher", "number", "higher", true, false, false, "");
    private static final PermissionDefinition HIGHER_NO_UNLIMITED = new PermissionDefinition("x.h2", "number", "higher", false, false, false, "");
    private static final PermissionDefinition LOWER = new PermissionDefinition("x.lower", "number", "lower", false, false, false, "");

    private static PermissionValue combine(PermissionDefinition d, PermissionValue... sources) {
        return EffectivePermissionCalculator.combine(d, List.of(sources), false);
    }

    @Test
    void booleanIsGrantedByAnySource() {
        assertThat(combine(FLAG, NOT_SET, GRANTED)).isEqualTo(GRANTED);
        assertThat(combine(FLAG)).isEqualTo(NOT_SET);
        assertThat(combine(FLAG, NOT_SET)).isEqualTo(NOT_SET);
    }

    @Test
    void booleanCanBeGrantedImplicitlyByTheAdminRole() {
        assertThat(EffectivePermissionCalculator.combine(FLAG, List.of(), true)).isEqualTo(GRANTED);
    }

    @Test
    void higherTakesTheLargestValue() {
        assertThat(combine(HIGHER, ofNumber(2), ofNumber(7), ofNumber(5))).isEqualTo(ofNumber(7));
    }

    @Test
    void lowerTakesTheSmallestValue() {
        assertThat(combine(LOWER, ofNumber(2), ofNumber(7), ofNumber(5))).isEqualTo(ofNumber(2));
    }

    @Test
    void zeroIsAValueAndNotTheSameAsNotSet() {
        assertThat(combine(HIGHER, ofNumber(0))).isEqualTo(ofNumber(0)).isNotEqualTo(NOT_SET);
        assertThat(combine(HIGHER)).isEqualTo(NOT_SET);
    }

    @Test
    void aSourceThatDoesNotSetTheValueDoesNotInfluenceLower() {
        // Wäre "nicht gesetzt" wie 0 behandelt, käme hier 0 heraus.
        assertThat(combine(LOWER, NOT_SET, ofNumber(3))).isEqualTo(ofNumber(3));
        assertThat(combine(LOWER, NOT_SET, NOT_SET)).isEqualTo(NOT_SET);
    }

    @Test
    void zeroInLowerDirectionWinsAgainstOtherNumbers() {
        assertThat(combine(LOWER, ofNumber(0), ofNumber(3))).isEqualTo(ofNumber(0));
    }

    @Test
    void unlimitedBeatsEveryNumberWhenDirectionIsHigher() {
        assertThat(combine(HIGHER, ofNumber(1000), UNLIMITED, ofNumber(5))).isEqualTo(UNLIMITED);
    }

    @Test
    void unlimitedIsIgnoredWhenTheDefinitionDoesNotAllowIt() {
        assertThat(combine(HIGHER_NO_UNLIMITED, UNLIMITED, ofNumber(5))).isEqualTo(ofNumber(5));
        assertThat(combine(HIGHER_NO_UNLIMITED, UNLIMITED)).isEqualTo(NOT_SET);
    }

    @Test
    void unlimitedIsIgnoredInLowerDirection() {
        assertThat(combine(LOWER, UNLIMITED, ofNumber(4))).isEqualTo(ofNumber(4));
    }
}
