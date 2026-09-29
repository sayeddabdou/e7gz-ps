package ps.e7gz.architecture.timefixture;

import java.time.Clock;

public class CallsClockSystemUtc {
    Clock clock() {
        return Clock.systemUTC();
    }
}
