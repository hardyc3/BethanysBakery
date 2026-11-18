package com.bethanysbakery.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.config.MediaProperties;
import com.bethanysbakery.api.support.CatalogTestImages;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import org.junit.jupiter.api.Test;

class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor(
        new MediaProperties("./build/test-media", 10_485_760, 1600, 40_000_000, 10_000));

    @Test
    void normalizesExifJpegCapsLongEdgeAndBuildsVariants() throws Exception {
        byte[] source = CatalogTestImages.jpegWithExif(2200, 1400);
        assertThat(new String(source, StandardCharsets.ISO_8859_1)).contains("Exif");

        ImageProcessor.NormalizedImage result = processor.normalize(source);

        // Orientation 6 rotates the 2200x1400 source to 1400x2200 before the 1600 cap.
        assertThat(result.height()).isEqualTo(1600);
        assertThat(result.width()).isLessThan(1600);
        assertThat(result.variants()).containsKeys(400, 800, 1600);
        assertThat(new String(result.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        assertThat(new String(result.bytes(), StandardCharsets.ISO_8859_1).substring(0, 4)).isEqualTo("RIFF");
        assertThat(result.variants().get(400)).isNotEqualTo(result.bytes());
    }

    @Test
    void acceptsPngAndWebp() throws Exception {
        assertThat(processor.normalize(CatalogTestImages.png(1200, 800)).width()).isEqualTo(1200);
        assertThat(processor.normalize(CatalogTestImages.webp(900, 700)).width()).isEqualTo(900);
    }

    @Test
    void skipsVariantsLargerThanNormalizedSource() throws Exception {
        ImageProcessor.NormalizedImage result = processor.normalize(CatalogTestImages.png(600, 400));
        assertThat(result.variants()).containsOnlyKeys(400);
        assertThat(result.width()).isEqualTo(600);
    }

    @Test
    void rejectsSvgContentWithUnsupportedMediaType() {
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> processor.normalize(svg))
            .isInstanceOf(AuthException.class)
            .extracting(ex -> ((AuthException) ex).code())
            .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void rejectsDeclaredDimensionsBeyondSafeDecodeBound() {
        assertThatThrownBy(() -> processor.normalize(CatalogTestImages.decompressionBombPng()))
            .isInstanceOf(AuthException.class)
            .extracting(ex -> ((AuthException) ex).code())
            .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void rejectsGifPdfAndUnknownBytes() {
        assertUnsupported("GIF89a".getBytes(StandardCharsets.US_ASCII));
        assertUnsupported("%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
        assertUnsupported("not an image".getBytes(StandardCharsets.US_ASCII));
    }

    /** Untrusted WebP must be decoded by pure-Java TwelveMonkeys, never the bundled native reader. */
    @Test
    void selectsPureJavaWebpReaderAndSejdaWriter() throws Exception {
        Method readerMethod = ImageProcessor.class.getDeclaredMethod("webpReader");
        readerMethod.setAccessible(true);
        ImageReader reader = (ImageReader) readerMethod.invoke(processor);
        assertThat(reader).isNotNull();
        assertThat(reader.getClass().getName()).startsWith("com.twelvemonkeys");
        reader.dispose();

        Method writerMethod = ImageProcessor.class.getDeclaredMethod("webpWriter");
        writerMethod.setAccessible(true);
        ImageWriter writer = (ImageWriter) writerMethod.invoke(processor);
        assertThat(writer).isNotNull();
        assertThat(writer.getClass().getName()).startsWith("com.luciad.imageio.webp");
        writer.dispose();
    }

    @Test
    void rejectsLargePixelAreaWithinSourceEdgeBound() {
        // 10000x10000 stays within maxSourceEdge but exceeds the 40M-pixel decode budget.
        assertThatThrownBy(() -> processor.normalize(CatalogTestImages.largeAreaPng()))
            .isInstanceOf(AuthException.class)
            .extracting(ex -> ((AuthException) ex).code())
            .isEqualTo("VALIDATION_ERROR");
    }

    private void assertUnsupported(byte[] bytes) {
        assertThatThrownBy(() -> processor.normalize(bytes))
            .isInstanceOf(AuthException.class)
            .extracting(ex -> ((AuthException) ex).code())
            .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }
}
