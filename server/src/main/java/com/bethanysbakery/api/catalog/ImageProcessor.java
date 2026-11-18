package com.bethanysbakery.api.catalog;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.config.MediaProperties;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

/** Validates, orients, normalizes and re-encodes uploaded images as WebP. */
@Component
public class ImageProcessor {

    private static final List<Integer> VARIANT_SIZES = List.of(400, 800, 1600);
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] RIFF_MAGIC = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_MAGIC = {'W', 'E', 'B', 'P'};

    private final MediaProperties properties;

    public ImageProcessor(MediaProperties properties) {
        this.properties = properties;
    }

    public NormalizedImage normalize(byte[] input) {
        String format = detectFormat(input);
        checkDeclaredDimensions(input, format);
        BufferedImage decoded = decode(input, format);
        BufferedImage oriented = applyOrientation(decoded, readOrientation(input));
        BufferedImage normalized = fit(oriented, properties.maxEdgePixels());
        byte[] original = encodeWebP(normalized);

        Map<Integer, byte[]> variantBytes = new LinkedHashMap<>();
        int longEdge = Math.max(normalized.getWidth(), normalized.getHeight());
        for (int size : VARIANT_SIZES) {
            if (size > longEdge) continue;
            BufferedImage scaled = size == longEdge ? normalized : fit(oriented, size);
            variantBytes.put(size, encodeWebP(scaled));
        }

        return new NormalizedImage(original, normalized.getWidth(), normalized.getHeight(),
            variantBytes, sha256(original));
    }

    private String detectFormat(byte[] input) {
        if (startsWith(input, JPEG_MAGIC)) return "jpeg";
        if (startsWith(input, PNG_MAGIC)) return "png";
        if (input.length >= 12 && startsWith(input, RIFF_MAGIC)
                && input[8] == WEBP_MAGIC[0] && input[9] == WEBP_MAGIC[1]
                && input[10] == WEBP_MAGIC[2] && input[11] == WEBP_MAGIC[3]) {
            return "webp";
        }
        throw AuthException.badRequest("UNSUPPORTED_MEDIA_TYPE",
            "Only JPEG, PNG and WebP images are supported");
    }

    private void checkDeclaredDimensions(byte[] input, String format) {
        Dimensions declared = switch (format) {
            case "png" -> pngDimensions(input);
            case "jpeg" -> jpegDimensions(input);
            case "webp" -> readerDimensions(input, format);
            default -> null;
        };
        if (declared == null) {
            throw AuthException.badRequest("UNSUPPORTED_MEDIA_TYPE", "Invalid image header");
        }
        if (declared.width() <= 0 || declared.height() <= 0 || declared.width() > properties.maxSourceEdge()
                || declared.height() > properties.maxSourceEdge()
                || (long) declared.width() * declared.height() > properties.maxDecodePixels()) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Image dimensions exceed safe decode bounds");
        }
    }

    private Dimensions pngDimensions(byte[] input) {
        if (input.length < 24) return null;
        ByteBuffer buffer = ByteBuffer.wrap(input, 16, 8);
        return new Dimensions(buffer.getInt(), buffer.getInt());
    }

    private Dimensions jpegDimensions(byte[] input) {
        int offset = 2;
        while (offset + 9 < input.length) {
            if ((input[offset] & 0xFF) != 0xFF) {
                offset++;
                continue;
            }
            int marker = input[offset + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                offset += 2;
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) break;
            int length = ((input[offset + 2] & 0xFF) << 8) | (input[offset + 3] & 0xFF);
            if (length < 2) break;
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                int height = ((input[offset + 5] & 0xFF) << 8) | (input[offset + 6] & 0xFF);
                int width = ((input[offset + 7] & 0xFF) << 8) | (input[offset + 8] & 0xFF);
                return new Dimensions(width, height);
            }
            offset += 2 + length;
        }
        return null;
    }

    private Dimensions readerDimensions(byte[] input, String format) {
        ImageReader reader = "webp".equals(format) ? webpReader() : firstReader(format);
        if (reader == null) return null;
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            reader.setInput(stream);
            return new Dimensions(reader.getWidth(0), reader.getHeight(0));
        } catch (IOException ex) {
            return null;
        } finally {
            reader.dispose();
        }
    }

    private BufferedImage decode(byte[] input, String format) {
        ByteArrayInputStream source = new ByteArrayInputStream(input);
        BufferedImage image;
        try {
            image = "webp".equals(format) ? decodeWebp(source) : ImageIO.read(source);
        } catch (IOException ex) {
            throw AuthException.badRequest("UNSUPPORTED_MEDIA_TYPE", "Image could not be decoded");
        }
        if (image == null) {
            throw AuthException.badRequest("UNSUPPORTED_MEDIA_TYPE", "Image could not be decoded");
        }
        return image;
    }

    private BufferedImage decodeWebp(ByteArrayInputStream source) throws IOException {
        ImageReader reader = webpReader();
        if (reader == null) {
            throw AuthException.badRequest("UNSUPPORTED_MEDIA_TYPE", "WebP codec unavailable");
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(source)) {
            reader.setInput(stream);
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    /** Forces the pure-Java TwelveMonkeys reader; never route untrusted WebP to the bundled JNI decoder. */
    private ImageReader webpReader() {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("webp");
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            if (reader.getClass().getName().startsWith("com.twelvemonkeys")) return reader;
            reader.dispose();
        }
        return null;
    }

    private ImageReader firstReader(String format) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        return readers.hasNext() ? readers.next() : null;
    }

    private int readOrientation(byte[] input) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(input));
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory == null) return 1;
            int orientation = directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            return orientation >= 1 && orientation <= 8 ? orientation : 1;
        } catch (Exception ignored) {
            return 1;
        }
    }

    private BufferedImage applyOrientation(BufferedImage source, int orientation) {
        return switch (orientation) {
            case 2 -> mirrorHorizontal(source);
            case 3 -> rotate180(source);
            case 4 -> mirrorVertical(source);
            case 5 -> rotate270(mirrorHorizontal(source));
            case 6 -> rotate90(source);
            case 7 -> rotate90(mirrorHorizontal(source));
            case 8 -> rotate270(source);
            default -> source;
        };
    }

    private BufferedImage rotate90(BufferedImage source) {
        AffineTransform transform = new AffineTransform();
        transform.translate(source.getHeight(), 0);
        transform.rotate(Math.PI / 2);
        return transform(source, transform);
    }

    private BufferedImage rotate180(BufferedImage source) {
        AffineTransform transform = new AffineTransform();
        transform.translate(source.getWidth(), source.getHeight());
        transform.rotate(Math.PI);
        return transform(source, transform);
    }

    private BufferedImage rotate270(BufferedImage source) {
        AffineTransform transform = new AffineTransform();
        transform.translate(0, source.getWidth());
        transform.rotate(-Math.PI / 2);
        return transform(source, transform);
    }

    private BufferedImage mirrorHorizontal(BufferedImage source) {
        AffineTransform transform = new AffineTransform();
        transform.translate(source.getWidth(), 0);
        transform.scale(-1, 1);
        return transform(source, transform);
    }

    private BufferedImage mirrorVertical(BufferedImage source) {
        AffineTransform transform = new AffineTransform();
        transform.translate(0, source.getHeight());
        transform.scale(1, -1);
        return transform(source, transform);
    }

    private BufferedImage transform(BufferedImage source, AffineTransform transform) {
        Point2D p1 = transform.transform(new Point2D.Double(0, 0), null);
        Point2D p2 = transform.transform(new Point2D.Double(source.getWidth(), 0), null);
        Point2D p3 = transform.transform(new Point2D.Double(0, source.getHeight()), null);
        Point2D p4 = transform.transform(new Point2D.Double(source.getWidth(), source.getHeight()), null);
        double minX = Math.min(Math.min(p1.getX(), p2.getX()), Math.min(p3.getX(), p4.getX()));
        double maxX = Math.max(Math.max(p1.getX(), p2.getX()), Math.max(p3.getX(), p4.getX()));
        double minY = Math.min(Math.min(p1.getY(), p2.getY()), Math.min(p3.getY(), p4.getY()));
        double maxY = Math.max(Math.max(p1.getY(), p2.getY()), Math.max(p3.getY(), p4.getY()));

        int width = Math.max(1, (int) Math.ceil(maxX - minX));
        int height = Math.max(1, (int) Math.ceil(maxY - minY));
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage result = new BufferedImage(width, height, type);

        AffineTransform adjusted = new AffineTransform();
        adjusted.translate(-minX, -minY);
        adjusted.concatenate(transform);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.transform(adjusted);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private BufferedImage fit(BufferedImage source, int longEdge) {
        if (Math.max(source.getWidth(), source.getHeight()) <= longEdge) return source;
        try {
            return Thumbnails.of(source).size(longEdge, longEdge).asBufferedImage();
        } catch (IOException ex) {
            throw new IllegalStateException("Image resize failed", ex);
        }
    }

    private byte[] encodeWebP(BufferedImage image) {
        ImageWriter writer = webpWriter();
        if (writer == null) throw new IllegalStateException("WebP ImageIO writer unavailable");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            writer.write(null, new IIOImage(image, null, null), params);
        } catch (IOException ex) {
            throw new IllegalStateException("WebP encoding failed", ex);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private ImageWriter webpWriter() {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("webp");
        while (writers.hasNext()) {
            ImageWriter writer = writers.next();
            if (writer.getClass().getName().startsWith("com.luciad.imageio.webp")) return writer;
            writer.dispose();
        }
        return null;
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) return false;
        }
        return true;
    }

    public record NormalizedImage(byte[] bytes, int width, int height, Map<Integer, byte[]> variants,
            String sha256) {}

    private record Dimensions(int width, int height) {}
}
