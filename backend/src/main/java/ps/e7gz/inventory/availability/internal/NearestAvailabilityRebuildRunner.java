package ps.e7gz.inventory.availability.internal;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection;

/**
 * The full rebuild command that ships with the application (arch 08 section 7): recomputes every
 * {@code inventory.venue_activity_availability} row when the application starts with the {@value #PROFILE} profile,
 * then the application ends (no web server, no workers in that profile):
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=availability-rebuild'
 * </pre>
 * Safe to rerun: each run recomputes every row at its own database command time (see
 * {@link NearestAvailabilityProjection#rebuild()}).
 */
@Component
@Profile(NearestAvailabilityRebuildRunner.PROFILE)
class NearestAvailabilityRebuildRunner implements ApplicationRunner {

    static final String PROFILE = "availability-rebuild";

    private final NearestAvailabilityProjection projection;

    NearestAvailabilityRebuildRunner(NearestAvailabilityProjection projection) {
        this.projection = projection;
    }

    @Override
    public void run(ApplicationArguments args) {
        projection.rebuild();
    }
}
