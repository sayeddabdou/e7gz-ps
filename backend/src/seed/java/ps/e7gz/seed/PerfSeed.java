package ps.e7gz.seed;

import static ps.e7gz.seed.DevSeed.point;
import static ps.e7gz.seed.Seeder.closedOn;
import static ps.e7gz.seed.Seeder.customOn;
import static ps.e7gz.seed.Seeder.daily;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.VenueAccountLanguage;
import ps.e7gz.common.geo.Coordinates;
import ps.e7gz.common.money.Money;
import ps.e7gz.venue.internal.OpeningHoursService;

/**
 * The perf seed (E7-28): launch-city volume ×10, for the load test (arch 11 §3 "sustained 10× projected peak", E7-105).
 * The launch figures are the Product Owner's (5 Oct 2026): 15 venues and 2,000 Customers, so the perf seed is 150
 * venues (about 1,000 rooms and tables, with prices and 120 days of Operating Sessions each) and 20,000 Customers. The
 * city's areas do not multiply: the venues share its six districts. Generated from a fixed random seed, so every run
 * writes the same shapes; venues are onboarded on a few threads, as several admins would.
 */
final class PerfSeed {

    /** How much to write. */
    record Volume(int areas, int venues, int customers) {

        /** PO decision 5 Oct 2026: the launch city. */
        static final Volume LAUNCH = new Volume(6, 15, 2_000);
        /** NFR-15 / arch 11 §3: ten times the launch. */
        static final Volume PERF = LAUNCH.times(10);

        Volume times(int factor) {
            return new Volume(areas, venues * factor, customers * factor);
        }
    }

    private static final String[][] AREAS = {
            {"Nasr City", "مدينة نصر", "30.0566", "31.3301"},
            {"Maadi", "المعادي", "29.9602", "31.2569"},
            {"Heliopolis", "مصر الجديدة", "30.0911", "31.3227"},
            {"Zamalek", "الزمالك", "30.0609", "31.2197"},
            {"Dokki", "الدقي", "30.0384", "31.2113"},
            {"New Cairo", "القاهرة الجديدة", "30.0074", "31.4913"}};
    private static final String[] BRANDS = {"Level Up", "Pixel", "Game Zone", "Controller", "Joystick", "Respawn",
            "Combo", "Arcade", "Checkpoint", "Boss Fight", "Power Up", "Console Club"};
    private static final String[] FIRST = {"Ahmed", "Mohamed", "Omar", "Youssef", "Mostafa", "Karim", "Mahmoud",
            "Hassan", "Ali", "Amr", "Ziad", "Seif", "Nour", "Malak", "Salma", "Farida", "Hana", "Yassin", "Adham",
            "Tamer", "Mariam", "Laila", "Khaled", "Hazem"};
    private static final String[] LAST = {"Hassan", "Ali", "Khaled", "Ibrahim", "Adel", "Samir", "Fathy", "Tarek",
            "Mahmoud", "Nabil", "Ashraf", "Hany", "Ahmed", "Hossam", "Wael", "Sherif", "Magdy", "Ramy", "Walid",
            "Fouad"};
    private static final int THREADS = 4;
    private static final long RANDOM_SEED = 28L;

    private PerfSeed() {
    }

    static void run(Seeder seeder, Volume volume) {
        Random random = new Random(RANDOM_SEED);
        seeder.admin();
        List<UUID> areaIds = new ArrayList<>();
        for (int i = 0; i < volume.areas(); i++) {
            String[] area = AREAS[i % AREAS.length];
            String suffix = i < AREAS.length ? "" : " " + (i / AREAS.length + 1);
            areaIds.add(seeder.area(area[0] + suffix, area[1] + suffix, point(area[2], area[3])));
        }

        List<Seeder.VenuePlan> plans = new ArrayList<>();
        for (int i = 0; i < volume.venues(); i++) {
            plans.add(venue(random, i, areaIds.get(i % areaIds.size()), AREAS[i % areaIds.size() % AREAS.length]));
        }
        onboard(seeder, plans);

        List<Seeder.CustomerPlan> customers = new ArrayList<>();
        for (int i = 0; i < volume.customers(); i++) {
            String first = FIRST[random.nextInt(FIRST.length)];
            String last = LAST[random.nextInt(LAST.length)];
            customers.add(new Seeder.CustomerPlan(first + " " + last, "player%05d".formatted(i + 1),
                    "011%08d".formatted(i + 1), "player%05d@example.com".formatted(i + 1), random.nextInt(10) != 0,
                    random.nextInt(366)));
        }
        seeder.customers(customers);
    }

    private static void onboard(Seeder seeder, List<Seeder.VenuePlan> plans) {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Seeder.SeededVenue>> done = new ArrayList<>();
            for (Seeder.VenuePlan plan : plans) {
                done.add(pool.submit(() -> seeder.venue(plan)));
            }
            for (Future<Seeder.SeededVenue> venue : done) {
                venue.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Perf seed interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("A venue of the perf seed failed", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Venue {@code i}: always PlayStation, often a pool or ping pong corner; mostly ACTIVE. */
    private static Seeder.VenuePlan venue(Random random, int i, UUID areaId, String[] area) {
        List<Activity> activities = new ArrayList<>(List.of(Activity.PLAYSTATION));
        boolean billiards = random.nextInt(100) < 40;
        boolean pingPong = random.nextInt(100) < 25;
        if (billiards) {
            activities.add(Activity.BILLIARDS);
        }
        if (pingPong) {
            activities.add(Activity.PING_PONG);
        }
        List<ConsoleType> rooms = new ArrayList<>();
        int roomCount = 3 + random.nextInt(6);
        for (int r = 0; r < roomCount; r++) {
            rooms.add(random.nextInt(100) < 60 ? ConsoleType.PS5 : ConsoleType.PS4);
        }
        int base = 30 + 5 * random.nextInt(7);
        Seeder.Prices prices = new Seeder.Prices(egp(base), egp(base + 20), egp(base + 20), egp(base + 40),
                egp(40 + 5 * random.nextInt(7)), egp(25 + 5 * random.nextInt(5)));
        int roll = random.nextInt(100);
        MarketplaceStatus status = roll < 88 ? MarketplaceStatus.ACTIVE
                : roll < 94 ? MarketplaceStatus.INACTIVE
                : roll < 97 ? MarketplaceStatus.ONBOARDING
                : MarketplaceStatus.SUSPENDED;
        Coordinates centroid = point(area[2], area[3]);
        Coordinates location = new Coordinates(jitter(random, centroid.lat()), jitter(random, centroid.lng()));
        return new Seeder.VenuePlan(areaId, "%s %s %d".formatted(BRANDS[i % BRANDS.length], area[0], i + 1),
                "%d Street %d, %s".formatted(1 + random.nextInt(90), 1 + random.nextInt(300), area[0]), location,
                "0227%06d".formatted(i + 1), activities, rooms, billiards ? 1 + random.nextInt(3) : 0,
                pingPong ? 1 + random.nextInt(2) : 0, prices, hours(random), status, "perf-venue-%03d".formatted(i + 1),
                random.nextInt(100) < 30 ? VenueAccountLanguage.ar : VenueAccountLanguage.en,
                random.nextInt(100) < 30 ? egp(base + 50) : null);
    }

    private static OpeningHoursService.Proposal hours(Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> daily("12:00", "02:00");
            case 1 -> daily("14:00", "03:00");
            case 2 -> daily("00:00", "00:00");
            case 3 -> closedOn(daily("10:00", "23:00"), 0);
            default -> customOn(daily("16:00", "00:00"), 4, "13:00", "04:00");
        };
    }

    /** Up to about 1.5 km from the area centre. */
    private static BigDecimal jitter(Random random, BigDecimal degrees) {
        return degrees.add(BigDecimal.valueOf(random.nextInt(3001) - 1500, 5)).setScale(5, RoundingMode.HALF_UP);
    }

    private static Money egp(int pounds) {
        return Money.of(BigDecimal.valueOf(pounds));
    }
}
