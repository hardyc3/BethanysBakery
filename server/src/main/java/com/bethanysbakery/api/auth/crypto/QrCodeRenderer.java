package com.bethanysbakery.api.auth.crypto;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Component;

@Component
public class QrCodeRenderer {
    private static final String DARK = "\u2588\u2588";
    private static final String LIGHT = "  ";

    public String render(String text) {
        try {
            int size = Math.max(21, Math.min(57, text.length() * 2 + 21));
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            StringBuilder sb = new StringBuilder();
            int quiet = 2;
            for (int y = -quiet; y < matrix.getHeight() + quiet; y++) {
                for (int x = -quiet; x < matrix.getWidth() + quiet; x++) {
                    boolean dark = x >= 0 && y >= 0 && x < matrix.getWidth() && y < matrix.getHeight() && matrix.get(x, y);
                    sb.append(dark ? DARK : LIGHT);
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("QR rendering failed", ex);
        }
    }
}
