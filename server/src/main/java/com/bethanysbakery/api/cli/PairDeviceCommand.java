package com.bethanysbakery.api.cli;

import com.bethanysbakery.api.auth.crypto.QrCodeRenderer;
import com.bethanysbakery.api.auth.device.DevicePairingService;
import com.bethanysbakery.api.config.BakeryProperties;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PairDeviceCommand {
    private final DevicePairingService pairingService;
    private final QrCodeRenderer qr;
    private final BakeryProperties.Auth config;

    public PairDeviceCommand(DevicePairingService pairingService, QrCodeRenderer qr, BakeryProperties properties) {
        this.pairingService = pairingService;
        this.qr = qr;
        this.config = properties.auth();
    }

    public int run(Map<String, String> args) {
        String code = pairingService.createPairingCode("cli");
        String pairUrl = config.appPairUrl();
        String adminApiUrl = config.appAdminApiUrl();
        System.out.println();
        System.out.println("Pairing code: " + code);
        System.out.println("Expires in: 15 minutes");
        System.out.println("Pair URL: " + (pairUrl == null || pairUrl.isBlank() ? "(APP_PAIR_URL not configured)" : pairUrl));
        System.out.println("Admin API URL: " + (adminApiUrl == null || adminApiUrl.isBlank() ? "(APP_ADMIN_API_URL not configured)" : adminApiUrl));
        if (pairUrl != null && !pairUrl.isBlank() && adminApiUrl != null && !adminApiUrl.isBlank()) {
            String qrValue = "{\"v\":1,\"pairUrl\":\"" + jsonEscape(pairUrl)
                    + "\",\"apiUrl\":\"" + jsonEscape(adminApiUrl)
                    + "\",\"code\":\"" + jsonEscape(code) + "\"}";
            System.out.println(qr.render(qrValue));
        }
        return 0;
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
