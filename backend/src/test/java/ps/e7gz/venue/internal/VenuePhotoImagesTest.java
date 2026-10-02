package ps.e7gz.venue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/** The stored-file checks behind confirm (arch 09 §1.5 VENUE_IMAGE_NOT_UPLOADED / NOT_ACCEPTED). */
class VenuePhotoImagesTest {

    @Test
    void theFileMustStartWithTheDeclaredTypesSignature() {
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(VenuePhotoImages.hasSignature(webp, "image/webp")).isTrue();
        assertThat(VenuePhotoImages.hasSignature(webp, "image/jpeg")).isFalse();
        assertThat(VenuePhotoImages.hasSignature(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, "image/jpeg")).isTrue();
        assertThat(VenuePhotoImages.hasSignature(new byte[0], "image/png")).isFalse();
    }

    @Test
    void aPictureLargerThanTheLimitIsNotAccepted() throws IOException {
        byte[] png = png(200, 100);

        assertThat(VenuePhotoImages.inspect(png, "image/png", 20_000)).isEqualTo(new VenuePhotoImages.Size(200, 100));
        assertThatThrownBy(() -> VenuePhotoImages.inspect(png, "image/png", 19_999))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.VENUE_IMAGE_NOT_ACCEPTED));
    }

    @Test
    void aFileThatCannotBeDecodedWasNotUploaded() {
        byte[] broken = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3};

        assertThatThrownBy(() -> VenuePhotoImages.inspect(broken, "image/png", 1_000_000))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.VENUE_IMAGE_NOT_UPLOADED));
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
