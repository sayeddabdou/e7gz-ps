package ps.e7gz.seed;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import ps.e7gz.admin.internal.SeedAdmins;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.OpeningDayMode;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.VenueAccountLanguage;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.geo.Coordinates;
import ps.e7gz.common.money.Money;
import ps.e7gz.geography.internal.Areas;
import ps.e7gz.identity.internal.PhoneNumbers;
import ps.e7gz.pricing.internal.Pricing;
import ps.e7gz.resource.internal.Resources;
import ps.e7gz.venue.internal.MarketplaceAction;
import ps.e7gz.venue.internal.OpeningHoursService;
import ps.e7gz.venue.internal.VenueAccounts;
import ps.e7gz.venue.internal.Venues;

/**
 * Writes seed data through the modules' own services, as the Platform Admin and the venues would through the API: every
 * row passes the same validation, commands, Owner Password checks, audit and database guards. Only Customers are
 * written by a seed command ({@link SeedCustomersCommand}), because registration needs an SMS code.
 */
final class Seeder {

    /** What one venue gets. {@code prices} and {@code hours} may be null: a venue still being set up. */
    record VenuePlan(UUID areaId, String name, String address, Coordinates location, String localPhone,
                     List<Activity> activities, List<ConsoleType> rooms, int poolTables, int pingPongTables,
                     Prices prices, OpeningHoursService.Proposal hours, MarketplaceStatus status, String login,
                     VenueAccountLanguage language, Money roomOverride) {
    }

    /** Venue default hourly prices; the billiards and ping pong rates are used only where offered. */
    record Prices(Money ps4Single, Money ps4Multi, Money ps5Single, Money ps5Multi, Money billiards, Money pingPong) {
    }

    record SeededVenue(UUID venueId, UUID accountId, List<UUID> resourceIds) {
    }

    record CustomerPlan(String fullName, String username, String localPhone, String email, boolean verified,
                        int joinedDaysAgo) {
    }

    private static final int CUSTOMERS_PER_COMMAND = 500;

    private final ApplicationContext context;
    private final Areas areas;
    private final Venues venues;
    private final VenueAccounts accounts;
    private final Resources resources;
    private final Pricing pricing;
    private final OpeningHoursService hours;
    private final CommandRunner runner;
    private final PasswordEncoder passwords;
    private UUID adminId;

    Seeder(ApplicationContext context) {
        this.context = context;
        this.areas = context.getBean(Areas.class);
        this.venues = context.getBean(Venues.class);
        this.accounts = context.getBean(VenueAccounts.class);
        this.resources = context.getBean(Resources.class);
        this.pricing = context.getBean(Pricing.class);
        this.hours = context.getBean(OpeningHoursService.class);
        this.runner = context.getBean(CommandRunner.class);
        this.passwords = context.getBean(PasswordEncoder.class);
    }

    /** The first Platform Admin, through the audited bootstrap; every admin action of the seed is theirs. */
    UUID admin() {
        adminId = inScope(() -> SeedAdmins.createFirst(context, SeedCredentials.ADMIN_EMAIL,
                SeedCredentials.ADMIN_NAME, SeedCredentials.PASSWORD.toCharArray()));
        return adminId;
    }

    UUID area(String name, String nameAr, Coordinates centroid) {
        return inScope(() -> areas.create(adminId, name, nameAr, centroid).id());
    }

    /**
     * Onboards one venue as the admin does (venue, account, rooms and tables), then sets prices and hours as the venue
     * does (Owner Password), then moves it to its marketplace status: ONBOARDING stays, INACTIVE completes onboarding,
     * ACTIVE and SUSPENDED complete it and then activate or suspend.
     */
    SeededVenue venue(VenuePlan plan) {
        return inScope(() -> {
            UUID venueId = venues.create(adminId, new Venues.NewVenue(plan.areaId(), plan.name(), plan.address(),
                    plan.location(), plan.localPhone(), plan.activities())).id();
            UUID accountId = accounts.create(adminId, venueId, plan.login(), SeedCredentials.PASSWORD,
                    SeedCredentials.OWNER_PIN, plan.language()).id();
            List<UUID> created = new ArrayList<>();
            for (ConsoleType console : plan.rooms()) {
                created.add(resources.create(adminId, venueId, Activity.PLAYSTATION, console).id());
            }
            for (int i = 0; i < plan.poolTables(); i++) {
                created.add(resources.create(adminId, venueId, Activity.BILLIARDS, null).id());
            }
            for (int i = 0; i < plan.pingPongTables(); i++) {
                created.add(resources.create(adminId, venueId, Activity.PING_PONG, null).id());
            }
            if (plan.prices() != null) {
                pricing.setDefaults(accountId, defaults(plan), SeedCredentials.OWNER_PIN);
                if (plan.roomOverride() != null && !plan.rooms().isEmpty()) {
                    pricing.setOverrides(accountId, created.getFirst(),
                            List.of(new Pricing.OverrideInput(PlayMode.MULTIPLAYER, plan.roomOverride())),
                            SeedCredentials.OWNER_PIN);
                }
            }
            if (plan.hours() != null) {
                String token = hours.preview(accountId, plan.hours()).previewToken();
                hours.commit(accountId, plan.hours(), token);
            }
            if (plan.status() != MarketplaceStatus.ONBOARDING) {
                status(venueId, MarketplaceAction.COMPLETE_ONBOARDING);
            }
            if (plan.status() == MarketplaceStatus.ACTIVE) {
                status(venueId, MarketplaceAction.ACTIVATE);
            } else if (plan.status() == MarketplaceStatus.SUSPENDED) {
                status(venueId, MarketplaceAction.SUSPEND);
            }
            return new SeededVenue(venueId, accountId, List.copyOf(created));
        });
    }

    /** Customers in batches, every one with the seed password (hashed once). */
    int customers(List<CustomerPlan> plans) {
        String hash = passwords.encode(SeedCredentials.PASSWORD);
        for (int from = 0; from < plans.size(); from += CUSTOMERS_PER_COMMAND) {
            List<SeedCustomersCommand.Row> batch = new ArrayList<>();
            for (CustomerPlan plan : plans.subList(from, Math.min(from + CUSTOMERS_PER_COMMAND, plans.size()))) {
                batch.add(new SeedCustomersCommand.Row(UUID.randomUUID(), plan.fullName(), plan.username(),
                        PhoneNumbers.toE164(plan.localPhone()).orElseThrow(
                                () -> new IllegalArgumentException("Not an Egyptian mobile: " + plan.localPhone())),
                        plan.email(), plan.verified(), plan.joinedDaysAgo()));
            }
            int offset = from;
            inScope(() -> runner.run(new SeedCustomersCommand(batch, hash, offset)));
        }
        return plans.size();
    }

    // ------------------------------------------------------------------------------------------ hours helpers

    /** The same hours every day; {@code open == close} is a full day. */
    static OpeningHoursService.Proposal daily(String open, String close) {
        LocalTime from = LocalTime.parse(open);
        LocalTime to = LocalTime.parse(close);
        return new OpeningHoursService.Proposal(from, to, from.equals(to), everyDay());
    }

    /** {@code base} with one weekday (0 = Monday) closed. */
    static OpeningHoursService.Proposal closedOn(OpeningHoursService.Proposal base, int weekday) {
        return withDay(base, new OpeningHoursService.Day(weekday, OpeningDayMode.CLOSED, null, null));
    }

    /** {@code base} with one weekday (0 = Monday) on custom hours. */
    static OpeningHoursService.Proposal customOn(OpeningHoursService.Proposal base, int weekday, String open,
                                                 String close) {
        return withDay(base, new OpeningHoursService.Day(weekday, OpeningDayMode.CUSTOM, LocalTime.parse(open),
                LocalTime.parse(close)));
    }

    private static OpeningHoursService.Proposal withDay(OpeningHoursService.Proposal base,
                                                        OpeningHoursService.Day day) {
        List<OpeningHoursService.Day> days = new ArrayList<>(base.days());
        days.set(day.weekday(), day);
        return new OpeningHoursService.Proposal(base.defaultOpen(), base.defaultClose(), base.defaultFullDay(), days);
    }

    private static List<OpeningHoursService.Day> everyDay() {
        List<OpeningHoursService.Day> days = new ArrayList<>();
        for (int weekday = 0; weekday < 7; weekday++) {
            days.add(new OpeningHoursService.Day(weekday, OpeningDayMode.DEFAULT, null, null));
        }
        return days;
    }

    // ------------------------------------------------------------------------------------------ internals

    private static List<Pricing.DefaultInput> defaults(VenuePlan plan) {
        Prices p = plan.prices();
        List<Pricing.DefaultInput> rows = new ArrayList<>();
        if (plan.activities().contains(Activity.PLAYSTATION)) {
            rows.add(ps(ConsoleType.PS4, PlayMode.SINGLE, p.ps4Single()));
            rows.add(ps(ConsoleType.PS4, PlayMode.MULTIPLAYER, p.ps4Multi()));
            rows.add(ps(ConsoleType.PS5, PlayMode.SINGLE, p.ps5Single()));
            rows.add(ps(ConsoleType.PS5, PlayMode.MULTIPLAYER, p.ps5Multi()));
        }
        if (plan.activities().contains(Activity.BILLIARDS)) {
            rows.add(new Pricing.DefaultInput(Activity.BILLIARDS, null, null, p.billiards()));
        }
        if (plan.activities().contains(Activity.PING_PONG)) {
            rows.add(new Pricing.DefaultInput(Activity.PING_PONG, null, null, p.pingPong()));
        }
        return rows;
    }

    private static Pricing.DefaultInput ps(ConsoleType console, PlayMode mode, Money price) {
        return new Pricing.DefaultInput(Activity.PLAYSTATION, console, mode, price);
    }

    private void status(UUID venueId, MarketplaceAction action) {
        venues.changeStatus(adminId, venueId, action, "Seed data (E7-28)");
    }

    /** Each seeded action is one correlated operation, as one API request would be. */
    private static <T> T inScope(Supplier<T> action) {
        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
            return action.get();
        }
    }
}
