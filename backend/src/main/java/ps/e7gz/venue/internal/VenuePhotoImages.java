package ps.e7gz.venue.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.coobird.thumbnailator.Thumbnails;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/**
 * Checks a stored photo and makes its copies (PO decision 3 Oct 2026). The copies are written fresh, so they carry no
 * GPS/EXIF or other metadata; the EXIF orientation is applied first so a phone photo stays upright. A PNG copy stays PNG,
 * JPEG and WebP copies are JPEG (Java cannot write WebP); transparency becomes white in a JPEG copy.
 */
final class VenuePhotoImages {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private VenuePhotoImages() {
    }

    record Size(int width, int height) {

        long pixels() {
            return (long) width * height;
        }
    }

    /**
     * The stored bytes must really be the declared type and decodable (VENUE_IMAGE_NOT_UPLOADED, arch 09 §1.5: "the
     * stored file does not match the declared type"); a picture larger than {@code maxPixels} is VENUE_IMAGE_NOT_ACCEPTED,
     * so a small file that unpacks into a huge picture never reaches the copy job. Reads the header only.
     */
    static Size inspect(byte[] bytes, String contentType, long maxPixels) {
        if (!hasSignature(bytes, contentType)) {
            throw new ApiException(ApiErrorCode.VENUE_IMAGE_NOT_UPLOADED);
        }
        Size size = size(bytes, contentType);
        if (size.pixels() > maxPixels) {
            throw new ApiException(ApiErrorCode.VENUE_IMAGE_NOT_ACCEPTED);
        }
        return size;
    }

    static boolean hasSignature(byte[] bytes, String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> startsWith(bytes, JPEG, 0);
            case "image/png" -> startsWith(bytes, PNG, 0);
            case "image/webp" -> startsWith(bytes, "RIFF".getBytes(), 0) && startsWith(bytes, "WEBP".getBytes(), 8);
            default -> false;
        };
    }

    private static Size size(byte[] bytes, String contentType) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(contentType);
            if (in == null || !readers.hasNext()) {
                throw new ApiException(ApiErrorCode.VENUE_IMAGE_NOT_UPLOADED);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new Size(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException unreadable) {
            if (unreadable instanceof ApiException api) {
                throw api;
            }
            throw new ApiException(ApiErrorCode.VENUE_IMAGE_NOT_UPLOADED);
        }
    }

    /** A copy whose longest side is at most {@code maxSide}; a smaller photo is not enlarged. */
    static byte[] copy(byte[] original, Size size, int maxSide, String copyContentType) {
        boolean png = "image/png".equals(copyContentType);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.Builder<?> builder = Thumbnails.of(new ByteArrayInputStream(original)).useExifOrientation(true);
            builder = Math.max(size.width(), size.height()) > maxSide ? builder.size(maxSide, maxSide) : builder.scale(1);
            if (png) {
                builder.outputFormat("png");
            } else {
                builder.addFilter(VenuePhotoImages::onWhite).outputFormat("jpg").outputQuality(0.85f);
            }
            builder.toOutputStream(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not make a photo copy", e);
        }
    }

    /** JPEG has no transparency: draw the picture on white, as an RGB image. */
    private static BufferedImage onWhite(BufferedImage image) {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix, int offset) {
        return bytes.length >= offset + prefix.length
                && Arrays.equals(bytes, offset, offset + prefix.length, prefix, 0, prefix.length);
    }
}
