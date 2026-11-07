package dev.lyy.friendlysync;

import dev.lyy.friendlysync.location.LocationValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class InvalidCoordinatesTest {

    @Test
    @DisplayName("Invalid Coordinates: LocationValidator strictly rejects NaN, Infinity, and out-of-bounds coordinates")
    void testCoordinateValidation() {
        // Valid coordinates
        assertThat(LocationValidator.isValid(100.0, 64.0, -100.0, 0.0f, 0.0f)).isTrue();

        // NaN checks
        assertThat(LocationValidator.isValid(Double.NaN, 64.0, 0.0, 0.0f, 0.0f)).isFalse();
        assertThat(LocationValidator.isValid(0.0, Double.NaN, 0.0, 0.0f, 0.0f)).isFalse();
        assertThat(LocationValidator.isValid(0.0, 64.0, Double.NaN, 0.0f, 0.0f)).isFalse();
        assertThat(LocationValidator.isValid(0.0, 64.0, 0.0, Float.NaN, 0.0f)).isFalse();
        assertThat(LocationValidator.isValid(0.0, 64.0, 0.0, 0.0f, Float.NaN)).isFalse();

        // Infinity checks
        assertThat(LocationValidator.isValid(Double.POSITIVE_INFINITY, 64.0, 0.0, 0.0f, 0.0f)).isFalse();
        assertThat(LocationValidator.isValid(0.0, Double.NEGATIVE_INFINITY, 0.0, 0.0f, 0.0f)).isFalse();
        assertThat(LocationValidator.isValid(0.0, 64.0, 0.0, Float.POSITIVE_INFINITY, 0.0f)).isFalse();

        // World boundary checks (> 30,000,000 blocks or extreme Y)
        assertThat(LocationValidator.isValid(35_000_000.0, 64.0, 0.0, 0.0f, 0.0f)).isFalse();
        assertThat(LocationValidator.isValid(0.0, 5000.0, 0.0, 0.0f, 0.0f)).isFalse();
        assertThat(LocationValidator.isValid(0.0, -4000.0, 0.0, 0.0f, 0.0f)).isFalse();
    }

    @Test
    @DisplayName("Invalid Coordinates: Yaw and pitch angles are sanitized and clamped correctly")
    void testAngleSanitization() {
        // Pitch clamping [-90, 90]
        assertThat(LocationValidator.clampPitch(120.0f)).isEqualTo(90.0f);
        assertThat(LocationValidator.clampPitch(-150.0f)).isEqualTo(-90.0f);
        assertThat(LocationValidator.clampPitch(45.0f)).isEqualTo(45.0f);
        assertThat(LocationValidator.clampPitch(Float.NaN)).isEqualTo(0.0f);

        // Yaw normalization [-180, 180]
        assertThat(LocationValidator.normalizeYaw(0.0f)).isEqualTo(0.0f);
        assertThat(LocationValidator.normalizeYaw(180.0f)).isEqualTo(-180.0f);
        assertThat(LocationValidator.normalizeYaw(270.0f)).isEqualTo(-90.0f);
        assertThat(LocationValidator.normalizeYaw(-270.0f)).isEqualTo(90.0f);
        assertThat(LocationValidator.normalizeYaw(Float.NaN)).isEqualTo(0.0f);
    }
}
