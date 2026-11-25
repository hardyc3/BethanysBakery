package com.bethanysbakery.api.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bethanysbakery.api.auth.crypto.QrCodeRenderer;
import com.bethanysbakery.api.auth.device.DevicePairingService;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.support.TestProperties;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PairDeviceCommandTest {

    @Mock DevicePairingService pairingService;
    @Mock QrCodeRenderer qr;

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void captureOutput() {
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(originalOut);
    }

    @Test
    void printsPairingCodeAndReturnsSuccess() {
        BakeryProperties properties = TestProperties.defaults();
        when(pairingService.createPairingCode("cli")).thenReturn("PAIR-CODE-123");
        PairDeviceCommand command = new PairDeviceCommand(pairingService, qr, properties);

        int result = command.run(Map.of());

        assertThat(result).isEqualTo(0);
        String output = captured.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("PAIR-CODE-123", "15 minutes");
        verify(pairingService).createPairingCode("cli");
    }

    @Test
    void rendersJsonPairingQrWithExactAppUrls() {
        BakeryProperties.Auth auth = new BakeryProperties.Auth(
                TestProperties.JWT_SECRET,
                TestProperties.ENCRYPTION_KEY,
                null,
                null,
                "Cf-Authenticated-Client-Cert",
                "Cf-Authenticated-Client-Cert-Verified",
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "https://example.com",
                "https://bakery.cherry-home.com/admin/v1",
                "https://bakery.cherry-home.com/admin/v1/devices/pair");
        BakeryProperties properties = new BakeryProperties(
                "Test Bakery",
                "America/Denver",
                "123 Test St",
                "https://maps.google.com/?q=123+Test+St",
                "test@example.com",
                12,
                auth,
                new BakeryProperties.AdminApp("1.0.0", "1.0.0", "https://example.com/admin.apk", null),
                new BakeryProperties.Venmo(null, null));
        when(pairingService.createPairingCode("cli")).thenReturn("PAIR-CODE-123");
        PairDeviceCommand command = new PairDeviceCommand(pairingService, qr, properties);

        command.run(Map.of());

        verify(qr).render("{\"v\":1,\"pairUrl\":\"https://bakery.cherry-home.com/admin/v1/devices/pair\","
                + "\"apiUrl\":\"https://bakery.cherry-home.com/admin/v1\",\"code\":\"PAIR-CODE-123\"}");
    }
}
