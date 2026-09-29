package ps.e7gz.architecture.timefixture;

import java.time.Instant;

public class CallsInstantNow {
    Instant stamp() {
        return Instant.now();
    }
}
