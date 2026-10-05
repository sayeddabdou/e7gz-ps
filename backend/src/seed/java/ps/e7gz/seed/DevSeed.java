package ps.e7gz.seed;

import static ps.e7gz.seed.Seeder.closedOn;
import static ps.e7gz.seed.Seeder.customOn;
import static ps.e7gz.seed.Seeder.daily;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.VenueAccountLanguage;
import ps.e7gz.common.geo.Coordinates;
import ps.e7gz.common.money.Money;

/**
 * The dev seed (E7-28): a small, fixed Cairo marketplace to click through. Two areas with three venues each - ACTIVE,
 * INACTIVE and ONBOARDING; PS4 and PS5 rooms, pool and ping pong tables; default prices and one room override; hours
 * that cross midnight, run 24 hours, close one weekday or change on Friday - and 20 Customers, 3 not yet verified.
 * Every login uses {@link SeedCredentials}.
 */
final class DevSeed {

    static final Seeder.Prices PRICES = new Seeder.Prices(Money.of("40.00"), Money.of("60.00"), Money.of("60.00"),
            Money.of("90.00"), Money.of("50.00"), Money.of("35.00"));

    static final List<String> CUSTOMER_NAMES = List.of(
            "Ahmed Hassan", "Mohamed Ali", "Omar Khaled", "Youssef Ibrahim", "Mostafa Adel", "Karim Samir",
            "Mahmoud Fathy", "Hassan Tarek", "Ali Mahmoud", "Amr Nabil", "Ziad Ashraf", "Seif Hany", "Nour Ahmed",
            "Malak Hossam", "Salma Wael", "Farida Sherif", "Hana Magdy", "Yassin Ramy", "Adham Walid", "Tamer Fouad");
    /** The last three Customers have not verified their phone yet. */
    static final int UNVERIFIED = 3;

    private DevSeed() {
    }

    static void run(Seeder seeder) {
        seeder.admin();
        UUID nasrCity = seeder.area("Nasr City", "مدينة نصر", point("30.0566", "31.3301"));
        UUID maadi = seeder.area("Maadi", "المعادي", point("29.9602", "31.2569"));

        seeder.venue(new Seeder.VenuePlan(nasrCity, "Level Up Lounge", "14 Abbas El Akkad St, Nasr City",
                point("30.0590", "31.3378"), "0222600101", List.of(Activity.PLAYSTATION, Activity.BILLIARDS),
                List.of(ConsoleType.PS5, ConsoleType.PS5, ConsoleType.PS4, ConsoleType.PS4), 2, 0, PRICES,
                daily("12:00", "02:00"), MarketplaceStatus.ACTIVE, "levelup", VenueAccountLanguage.en,
                Money.of("100.00")));
        seeder.venue(new Seeder.VenuePlan(nasrCity, "Pixel Arena", "7 Makram Ebeid St, Nasr City",
                point("30.0623", "31.3456"), "0222600102", List.of(Activity.PLAYSTATION),
                List.of(ConsoleType.PS5, ConsoleType.PS5, ConsoleType.PS5, ConsoleType.PS4), 0, 0, PRICES,
                customOn(daily("14:00", "03:00"), 4, "13:00", "04:00"), MarketplaceStatus.ACTIVE, "pixelarena",
                VenueAccountLanguage.ar, null));
        seeder.venue(new Seeder.VenuePlan(nasrCity, "Cue & Console", "22 Youssef Abbas St, Nasr City",
                point("30.0541", "31.3412"), "0222600103", List.of(Activity.PLAYSTATION, Activity.BILLIARDS),
                List.of(ConsoleType.PS4), 1, 0, null, null, MarketplaceStatus.ONBOARDING, "cueconsole",
                VenueAccountLanguage.en, null));

        seeder.venue(new Seeder.VenuePlan(maadi, "Game Zone Maadi", "9 Road 9, Maadi", point("29.9608", "31.2583"),
                "0225200201", List.of(Activity.PLAYSTATION, Activity.PING_PONG),
                List.of(ConsoleType.PS5, ConsoleType.PS5, ConsoleType.PS4), 0, 2, PRICES, daily("00:00", "00:00"),
                MarketplaceStatus.ACTIVE, "gamezone", VenueAccountLanguage.en, null));
        seeder.venue(new Seeder.VenuePlan(maadi, "The Controller Cafe", "31 Road 233, Degla, Maadi",
                point("29.9580", "31.2770"), "0225200202", List.of(Activity.PLAYSTATION),
                List.of(ConsoleType.PS5, ConsoleType.PS4), 0, 0, PRICES, closedOn(daily("10:00", "23:00"), 0),
                MarketplaceStatus.ACTIVE, "controller", VenueAccountLanguage.ar, null));
        seeder.venue(new Seeder.VenuePlan(maadi, "Spin Club", "4 Road 77, Maadi", point("29.9655", "31.2490"),
                "0225200203", List.of(Activity.BILLIARDS, Activity.PING_PONG), List.of(), 2, 2, PRICES,
                daily("16:00", "00:00"), MarketplaceStatus.INACTIVE, "spinclub", VenueAccountLanguage.en, null));

        List<Seeder.CustomerPlan> customers = new ArrayList<>();
        for (int i = 0; i < CUSTOMER_NAMES.size(); i++) {
            String name = CUSTOMER_NAMES.get(i);
            boolean verified = i < CUSTOMER_NAMES.size() - UNVERIFIED;
            customers.add(new Seeder.CustomerPlan(name, "player%02d".formatted(i + 1), localPhone(i + 1),
                    name.toLowerCase(Locale.ROOT).replace(' ', '.') + "@example.com", verified, 30 - i));
        }
        seeder.customers(customers);
    }

    /** Customer {@code n} (1-based) logs in with {@code 0100000000n}: 01000000001 … 01000000020. */
    static String localPhone(int n) {
        return "010%08d".formatted(n);
    }

    static Coordinates point(String lat, String lng) {
        return new Coordinates(new BigDecimal(lat), new BigDecimal(lng));
    }
}
