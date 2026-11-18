package com.bethanysbakery.api.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Deterministic image fixtures for S3 media tests. */
public final class CatalogTestImages {

    private CatalogTestImages() {}

    public static byte[] jpeg(int width, int height) throws IOException {
        return image(width, height, "jpeg");
    }

    public static byte[] png(int width, int height) throws IOException {
        return image(width, height, "png");
    }

    public static byte[] webp(int width, int height) throws IOException {
        return image(width, height, "webp");
    }

    public static byte[] jpegWithExif(int width, int height) throws IOException {
        byte[] jpeg = jpeg(width, height);
        byte[] exif = exifOrientationSix();
        byte[] result = new byte[jpeg.length + exif.length];
        System.arraycopy(jpeg, 0, result, 0, 2);
        System.arraycopy(exif, 0, result, 2, exif.length);
        System.arraycopy(jpeg, 2, result, 2 + exif.length, jpeg.length - 2);
        return result;
    }

    /** Minimal PNG with a declared 20000x20000 header and no pixel data. */
    public static byte[] decompressionBombPng() {
        return pngWithDeclaredDimensions(20_000, 20_000);
    }

    /** Minimal PNG at 10000x10000: within the source-edge bound but over the pixel budget. */
    public static byte[] largeAreaPng() {
        return pngWithDeclaredDimensions(10_000, 10_000);
    }

    private static byte[] pngWithDeclaredDimensions(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13 + 12);
        ihdr.putInt(13);
        ihdr.put(new byte[] {'I', 'H', 'D', 'R'});
        ihdr.putInt(width);
        ihdr.putInt(height);
        ihdr.put((byte) 8);
        ihdr.put((byte) 2);
        ihdr.put((byte) 0);
        ihdr.put((byte) 0);
        ihdr.put((byte) 0);
        byte[] header = ihdr.array();
        CRC32 crc = new CRC32();
        crc.update(header, 4, 13);
        byte[] checksum = ByteBuffer.allocate(4).putInt((int) crc.getValue()).array();
        System.arraycopy(checksum, 0, header, 17, 4);

        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] result = new byte[signature.length + header.length];
        System.arraycopy(signature, 0, result, 0, signature.length);
        System.arraycopy(header, 0, result, signature.length, header.length);
        return result;
    }

    private static byte[] image(int width, int height, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0x42, 0x7B, 0xF2));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(width / 4, height / 4, Math.max(1, width / 2), Math.max(1, height / 2));
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) {
            throw new IOException("No ImageIO writer for " + format);
        }
        return out.toByteArray();
    }

    private static byte[] exifOrientationSix() {
        // APP1/Exif/TIFF little-endian with one IFD0 entry: Orientation (0x0112, SHORT) = 6.
        byte[] payload = new byte[32];
        byte[] label = {'E', 'x', 'i', 'f', 0, 0};
        System.arraycopy(label, 0, payload, 0, label.length);
        payload[6] = 'I';
        payload[7] = 'I';
        payload[8] = 0x2A;
        payload[10] = 8; // IFD0 offset from TIFF header (payload index 14)
        payload[14] = 1; // IFD0 entry count
        payload[16] = 0x12; // orientation tag 0x0112
        payload[17] = 0x01;
        payload[18] = 3; // SHORT
        payload[20] = 1; // value count
        payload[24] = 6; // value: rotate 90 degrees clockwise
        byte[] segment = new byte[payload.length + 4];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) 0xE1;
        int length = payload.length + 2;
        segment[2] = (byte) (length >> 8);
        segment[3] = (byte) length;
        System.arraycopy(payload, 0, segment, 4, payload.length);
        return segment;
    }
}
