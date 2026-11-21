package com.bethanysbakery.api.auth.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class DevicePairingServiceTest {

    @Test
    void mapsOnlySingleActiveUniqueViolationToConflict() {
        assertThat(DevicePairingService.isSingleActiveDeviceViolation(violation(
            "ERROR: duplicate key value violates unique constraint \"uq_admin_device_single_active\"", "23505")))
            .isTrue();
    }

    @Test
    void leavesCertificateConflictsAndNonUniqueViolationsAlone() {
        assertThat(DevicePairingService.isSingleActiveDeviceViolation(violation(
            "ERROR: duplicate key value violates unique constraint \"admin_device_cert_fingerprint_sha256_key\"",
            "23505")))
            .isFalse();
        assertThat(DevicePairingService.isSingleActiveDeviceViolation(violation(
            "ERROR: null value in column \"name\" violates not-null constraint", "23502")))
            .isFalse();
        assertThat(DevicePairingService.isSingleActiveDeviceViolation(violation(
            "uq_admin_device_single_active", "23502")))
            .isFalse();
    }

    private static DataIntegrityViolationException violation(String message, String sqlState) {
        return new DataIntegrityViolationException("could not execute statement", new SQLException(message, sqlState));
    }
}
