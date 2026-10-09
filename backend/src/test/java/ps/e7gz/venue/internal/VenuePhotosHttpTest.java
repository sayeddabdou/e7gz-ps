package ps.e7gz.venue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.scheduling.api.Recurrence;
import ps.e7gz.scheduling.api.RecurringJob;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.ObjectStoreTestContainer;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.venue.api.VenuePhotos;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * E7-23 PR 3: venue photos through the Platform Admin routes (arch 09 §5, PCR-005), against the migrated database and
 * a real S3 server. The task's "done when": upload → confirm → listed with a signed GET URL; wrong type, too large, an
 * 11th photo and confirm before upload are refused with their codes; a removed photo is no longer listed but its row
 * and file remain; each change has an audit row. Plus the PO decisions of 3 Oct 2026 (copies without GPS/EXIF, link
 * lifetimes, nightly clean-up).
 */
@AutoConfigureMockMvc
class VenuePhotosHttpTest extends IntegrationTest {

    private static final String BASE = "/api/v1/admin/venues/";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    VenuePhotos photos;

    @Autowired
    CommandRunner runner;

    @Autowired
    ObjectStore store;

    @Autowired
    VenuePhotoProperties properties;

    @Autowired
    DataSource appDataSource;

    @Autowired
    Clock clock;

    private final JdbcClient owner = JdbcClient.create(new SimpleDriverDataSource(
            new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));
    private final HttpClient http = HttpClient.newHttpClient();
    private final S3Client s3 = ObjectStoreTestContainer.client();

    private String token;
    private UUID venue;
    private VenuePhotoJobs jobs;

    @BeforeEach
    void anAdminAndAVenue() {
        token = accessTokens.issue(AuthSubjectType.ADMIN, AuditFixtures.admin());
        venue = venue();
        jobs = new VenuePhotoJobs(runner, store, properties, clock, appDataSource);   // the worker profile's jobs, run by hand
    }

    // ------------------------------------------------------------------------------------------ upload and copies

    @Test
    void anUploadedPhotoIsConfirmedListedAndGetsCopiesWithoutMetadata() throws Exception {
        byte[] original = withExifOrientation(jpeg(2000, 1000), 6);       // a phone photo taken in portrait
        JsonNode upload = start("image/jpeg", original.length).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString().transform(this::data);
        UUID id = UUID.fromString(upload.path("image").path("id").asText());
        assertThat(upload.path("image").path("smallUrl").isNull()).isTrue();
        assertThat(Instant.parse(upload.path("uploadExpiresAt").asText()))
                .isBetween(clock.instant().plus(Duration.ofMinutes(9)), clock.instant().plus(Duration.ofMinutes(11)));

        assertThat(putFile(upload.path("uploadUrl").asText(), original, "image/jpeg")).isEqualTo(200);
        JsonNode confirmed = data(call(post(BASE + venue + "/images/" + id + "/confirm"), null)
                .andExpect(status().isOk()));
        assertThat(confirmed.path("smallUrl").isNull()).isTrue();         // the copies are not made yet
        assertThat(auditCount("VENUE_PHOTO_ADDED", id)).isEqualTo(1);
        assertThat(list()).hasSize(1);

        jobs.makeCopies();

        JsonNode listed = list().getFirst();
        assertThat(listed.path("id").asText()).isEqualTo(id.toString());
        assertThat(Instant.parse(listed.path("urlsExpireAt").asText()))
                .isBetween(clock.instant().plus(Duration.ofMinutes(59)), clock.instant().plus(Duration.ofMinutes(61)));
        byte[] small = download(listed.path("smallUrl").asText());
        byte[] medium = download(listed.path("mediumUrl").asText());

        // Upright (EXIF orientation applied), resized, and no EXIF/GPS left; the original keeps its metadata.
        assertThat(size(small)).isEqualTo(new int[] {240, 480});
        assertThat(size(medium)).isEqualTo(new int[] {640, 1280});
        assertThat(contains(small, "Exif")).isFalse();
        assertThat(contains(medium, "Exif")).isFalse();
        assertThat(contains(stored("venues/" + venue + "/" + id + ".jpg"), "Exif")).isTrue();

        // Customer discovery (E7-34) gets only the copies.
        assertThat(photos.cardImages(List.of(venue))).containsKey(venue);
        assertThat(photos.gallery(venue)).hasSize(1);
        assertThat(processedAt(id)).isNotNull();
    }

    @Test
    void aPngCopyStaysPngAndASmallPhotoIsNotEnlarged() throws Exception {
        UUID id = uploadAndConfirm(png(300, 200), "image/png");
        jobs.makeCopies();

        JsonNode listed = list().getFirst();
        assertThat(listed.path("id").asText()).isEqualTo(id.toString());
        byte[] small = download(listed.path("smallUrl").asText());
        assertThat(contains(small, "PNG")).isTrue();
        assertThat(size(small)).isEqualTo(new int[] {300, 200});
    }

    /** E7-40: both photo jobs are recurring system jobs of the worker (technical.scheduled_job), no Spring timers. */
    @Test
    void theCopiesAndTheCleanUpAreRecurringJobsOfTheWorker() throws Exception {
        RecurringJob copies = jobs.venuePhotoCopiesJob();
        RecurringJob cleanUp = jobs.venuePhotoCleanupJob();
        assertThat(copies.type()).isEqualTo("VENUE_PHOTO_COPIES");
        assertThat(copies.recurrence()).isEqualTo(Recurrence.every(Duration.ofSeconds(30)));
        assertThat(cleanUp.type()).isEqualTo("VENUE_PHOTO_CLEANUP");
        assertThat(cleanUp.recurrence()).isEqualTo(Recurrence.nightlyAt(LocalTime.of(3, 30)));

        UUID id = uploadAndConfirm(jpeg(800, 600), "image/jpeg");
        copies.run(null);                                              // what the worker runs each time it is due

        assertThat(processed(id)).isTrue();
    }

    // ------------------------------------------------------------------------------------------ refusals

    @Test
    void aTypeOtherThanJpegPngOrWebpIsRefused() throws Exception {
        start("image/gif", 1000).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VENUE_IMAGE_NOT_ACCEPTED"))
                .andExpect(jsonPath("$.error.field").value("contentType"));
    }

    @Test
    void aFileOverFiveMegabytesIsRefused() throws Exception {
        start("image/jpeg", 5 * 1024 * 1024 + 1).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VENUE_IMAGE_NOT_ACCEPTED"))
                .andExpect(jsonPath("$.error.field").value("sizeBytes"));
    }

    @Test
    void anEleventhPhotoIsRefusedAtStartAndAtConfirm() throws Exception {
        for (int i = 1; i <= 9; i++) {
            visibleRow(i);
        }
        byte[] file = jpeg(100, 100);
        JsonNode upload = data(start("image/jpeg", file.length).andExpect(status().isCreated()));
        putFile(upload.path("uploadUrl").asText(), file, "image/jpeg");
        visibleRow(10);                                                       // the tenth arrives meanwhile

        call(post(BASE + venue + "/images/" + upload.path("image").path("id").asText() + "/confirm"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VENUE_IMAGE_LIMIT_REACHED"));
        start("image/jpeg", file.length).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VENUE_IMAGE_LIMIT_REACHED"));
        assertThat(list()).hasSize(10);
    }

    @Test
    void confirmBeforeTheUploadIsRefused() throws Exception {
        JsonNode upload = data(start("image/jpeg", 1000).andExpect(status().isCreated()));

        call(post(BASE + venue + "/images/" + upload.path("image").path("id").asText() + "/confirm"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VENUE_IMAGE_NOT_UPLOADED"));
        assertThat(list()).isEmpty();
    }

    @Test
    void aFileThatIsNotTheDeclaredTypeIsRefusedAtConfirm() throws Exception {
        byte[] notAPng = jpeg(100, 100);
        JsonNode upload = data(start("image/png", notAPng.length).andExpect(status().isCreated()));
        assertThat(putFile(upload.path("uploadUrl").asText(), notAPng, "image/png")).isEqualTo(200);

        call(post(BASE + venue + "/images/" + upload.path("image").path("id").asText() + "/confirm"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VENUE_IMAGE_NOT_UPLOADED"));
    }

    /** The signed PUT is for exactly the declared type and size: storage refuses anything else. */
    @Test
    void theUploadLinkAcceptsOnlyTheDeclaredTypeAndSize() throws Exception {
        byte[] file = jpeg(100, 100);
        String url = data(start("image/jpeg", file.length).andExpect(status().isCreated())).path("uploadUrl").asText();

        assertThat(putFile(url, file, "image/png")).isNotEqualTo(200);
        byte[] bigger = new byte[file.length + 1000];
        System.arraycopy(file, 0, bigger, 0, file.length);
        assertThat(putFile(url, bigger, "image/jpeg")).isNotEqualTo(200);
        assertThat(putFile(url, file, "image/jpeg")).isEqualTo(200);
    }

    // ------------------------------------------------------------------------------------------ remove and reorder

    @Test
    void aRemovedPhotoIsHiddenButItsRowAndFileStay() throws Exception {
        UUID id = uploadAndConfirm(jpeg(100, 100), "image/jpeg");

        call(post(BASE + venue + "/images/" + id + "/remove"), null).andExpect(status().isNoContent());

        assertThat(list()).isEmpty();
        assertThat(owner.sql("SELECT removed_at IS NOT NULL FROM venue.venue_image WHERE id = :id").param("id", id)
                .query(Boolean.class).single()).isTrue();
        assertThat(stored("venues/" + venue + "/" + id + ".jpg")).isNotEmpty();
        assertThat(auditCount("VENUE_PHOTO_REMOVED", id)).isEqualTo(1);
        call(post(BASE + venue + "/images/" + id + "/remove"), null).andExpect(status().isNotFound());
    }

    @Test
    void photosAreReorderedAndTheFirstIsTheCardPhoto() throws Exception {
        UUID a = visibleRow(1);
        UUID b = visibleRow(2);
        UUID c = visibleRow(3);

        JsonNode reordered = data(call(put(BASE + venue + "/images/order"), Map.of("imageIds", List.of(c, a, b)))
                .andExpect(status().isOk()));

        assertThat(ids(reordered)).containsExactly(c.toString(), a.toString(), b.toString());
        assertThat(list().stream().map(n -> n.path("id").asText())).containsExactly(c.toString(), a.toString(),
                b.toString());
        assertThat(auditCount("VENUE_PHOTOS_REORDERED", venue)).isEqualTo(1);
    }

    @Test
    void anOrderThatDoesNotNameEveryVisiblePhotoOnceIsRefused() throws Exception {
        UUID a = visibleRow(1);
        UUID b = visibleRow(2);

        for (List<UUID> wrong : List.of(List.of(a), List.of(a, a), List.of(a, b, UUID.randomUUID()))) {
            call(put(BASE + venue + "/images/order"), Map.of("imageIds", wrong))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.field").value("imageIds"));
        }
        assertThat(auditCount("VENUE_PHOTOS_REORDERED", venue)).isZero();
    }

    @Test
    void anUnknownVenueOrAnotherVenuesPhotoIsNotFound() throws Exception {
        call(get(BASE + UUID.randomUUID() + "/images"), null).andExpect(status().isNotFound());
        UUID otherVenuesPhoto = visibleRowOf(venue(), 1);
        call(post(BASE + venue + "/images/" + otherVenuesPhoto + "/remove"), null).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------ nightly clean-up

    @Test
    void uploadsNotConfirmedWithinADayAreHiddenAndTheirFilesDeleted() throws Exception {
        UUID old = startedRow(Duration.ofHours(25));
        UUID recent = startedRow(Duration.ofHours(1));

        jobs.cleanUpAbandonedUploads();

        assertThat(removed(old)).isTrue();
        assertThat(removed(recent)).isFalse();
        assertThat(stored("venues/" + venue + "/" + old + ".jpg")).isEmpty();
        assertThat(stored("venues/" + venue + "/" + recent + ".jpg")).isNotEmpty();
    }

    @Test
    void photosWhoseCopiesFailDoNotHoldUpNewerOnes() throws Exception {
        // E7-24: more failing photos than one batch (on other venues: a venue holds at most 10), all older than a good one.
        List<UUID> broken = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            UUID id = visibleRowOf(venue(), 1);
            owner.sql("UPDATE venue.venue_image SET uploaded_at = now() - interval '1 hour' WHERE id = :id")
                    .param("id", id).update();
            broken.add(id);
        }
        try {
            UUID good = uploadAndConfirm(jpeg(800, 600), "image/jpeg");

            // Before E7-24 the same oldest 20 were picked on every run, so the good photo never got its turn. Now each
            // failed photo is set aside after one try, and the queue moves on (other tests may leave broken rows too).
            for (int run = 0; run < 10 && !processed(good); run++) {
                jobs.makeCopies();
            }

            assertThat(processed(good)).isTrue();
        } finally {
            // The other tests share this database and run the copy job without this test's set-aside list.
            owner.sql("UPDATE venue.venue_image SET removed_at = now() WHERE id = ANY(:ids)")
                    .param("ids", broken.toArray(UUID[]::new)).update();
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private UUID uploadAndConfirm(byte[] file, String type) throws Exception {
        JsonNode upload = data(start(type, file.length).andExpect(status().isCreated()));
        assertThat(putFile(upload.path("uploadUrl").asText(), file, type)).isEqualTo(200);
        UUID id = UUID.fromString(upload.path("image").path("id").asText());
        call(post(BASE + venue + "/images/" + id + "/confirm"), null).andExpect(status().isOk());
        return id;
    }

    private ResultActions start(String type, long size) throws Exception {
        return call(post(BASE + venue + "/images"), Map.of("contentType", type, "sizeBytes", size));
    }

    private List<JsonNode> list() throws Exception {
        List<JsonNode> items = new ArrayList<>();
        data(call(get(BASE + venue + "/images"), null).andExpect(status().isOk())).forEach(items::add);
        return items;
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.path("id").asText()));
        return ids;
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return data(result.andReturn().getResponse().getContentAsString());
    }

    private JsonNode data(String body) {
        try {
            return json.readTree(body).path("data");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private int putFile(String url, byte[] bytes, String type) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", type)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private byte[] download(String url) throws Exception {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private byte[] stored(String key) {
        try {
            return s3.getObjectAsBytes(r -> r.bucket(ObjectStoreTestContainer.BUCKET).key(key)).asByteArray();
        } catch (NoSuchKeyException missing) {
            return new byte[0];
        }
    }

    private UUID venue() {
        UUID area = UUID.randomUUID();
        owner.sql("INSERT INTO geo.area (id, name, is_active) VALUES (:id, :name, true)")
                .param("id", area).param("name", "Area " + area).update();
        UUID id = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
                        VALUES (:id, :area, :name, '12 Test St', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING',
                                'Africa/Cairo', '+20223456789')""")
                .param("id", id).param("area", area).param("name", "Venue " + id).update();
        return id;
    }

    private UUID visibleRow(int position) {
        return visibleRowOf(venue, position);
    }

    /** A confirmed photo written directly (no file): enough for limits, order and ownership. */
    private UUID visibleRowOf(UUID venueId, int position) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO venue.venue_image (id, venue_id, object_key, content_type, sort_order, uploaded_at)
                        VALUES (:id, :venue, :key, 'image/jpeg', :position, now())""")
                .param("id", id).param("venue", venueId).param("key", "venues/" + venueId + "/" + id + ".jpg")
                .param("position", position).update();
        return id;
    }

    /** An upload started {@code age} ago and never confirmed, with its file stored. */
    private UUID startedRow(Duration age) {
        UUID id = UUID.randomUUID();
        String key = "venues/" + venue + "/" + id + ".jpg";
        owner.sql("""
                        INSERT INTO venue.venue_image (id, venue_id, object_key, content_type, created_at)
                        VALUES (:id, :venue, :key, 'image/jpeg', :createdAt)""")
                .param("id", id).param("venue", venue).param("key", key)
                .param("createdAt", OffsetDateTime.ofInstant(clock.instant().minus(age), ZoneOffset.UTC)).update();
        s3.putObject(r -> r.bucket(ObjectStoreTestContainer.BUCKET).key(key).contentType("image/jpeg"),
                RequestBody.fromBytes(new byte[] {1, 2, 3}));
        return id;
    }

    private boolean removed(UUID id) {
        return owner.sql("SELECT removed_at IS NOT NULL FROM venue.venue_image WHERE id = :id").param("id", id)
                .query(Boolean.class).single();
    }

    private boolean processed(UUID id) {
        return owner.sql("SELECT processed_at IS NOT NULL FROM venue.venue_image WHERE id = :id").param("id", id)
                .query(Boolean.class).single();
    }

    private OffsetDateTime processedAt(UUID id) {
        return owner.sql("SELECT processed_at FROM venue.venue_image WHERE id = :id").param("id", id)
                .query(OffsetDateTime.class).single();
    }

    private long auditCount(String action, UUID subject) {
        return owner.sql("SELECT count(*) FROM audit.audit_event WHERE action = :action AND subject_id = :subject")
                .param("action", action).param("subject", subject).query(Long.class).single();
    }

    // ------------------------------------------------------------------------------------------ test pictures

    private static byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, width / 2, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Adds an EXIF block (APP1) with the given orientation and a GPS-like marker right after the JPEG start marker. */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {
                'M', 'M', 0, 42, 0, 0, 0, 8,                     // big-endian TIFF header, first IFD at 8
                0, 1,                                            // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,   // Orientation, SHORT, 1 value
                0, 0, 0, 0};                                     // no next IFD
        byte[] marker = "GPSLatitude 30.0561".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        app1.writeBytes(tiff);
        app1.writeBytes(marker);
        byte[] payload = app1.toByteArray();
        int length = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static int[] size(byte[] picture) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(picture));
        return new int[] {image.getWidth(), image.getHeight()};
    }

    private static boolean contains(byte[] bytes, String text) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(text);
    }
}
