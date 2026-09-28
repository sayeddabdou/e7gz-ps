package ps.e7gz.common.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LockSetTest {

    private final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void idsAreDeduplicatedAndSorted() {
        LockSet lockSet = LockSet.builder().users(b, a, b).venues(a).build();

        assertThat(lockSet.users()).containsExactly(a, b);
        assertThat(lockSet.venues()).containsExactly(a);
        assertThat(lockSet.resources()).isEmpty();
        assertThat(lockSet.tournaments()).isEmpty();
        assertThat(lockSet.newTournaments()).isEmpty();
    }

    @Test
    void nullIdsAreRefused() {
        assertThatThrownBy(() -> LockSet.builder().users(a, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void existingAndNewTournamentsMustBeDisjoint() {
        assertThatThrownBy(() -> LockSet.builder().tournaments(a).newTournaments(a).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adminScopeLocksOnlyUsersAndVenues() {
        UUID admin = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();

        assertThat(CommandScope.admin(admin, correlation, LockSet.builder().users(a).venues(b).build()).lockSet().users())
                .containsExactly(a);
        assertThatThrownBy(() -> CommandScope.admin(admin, correlation, LockSet.builder().resources(a).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandScope.admin(admin, correlation, LockSet.builder().tournaments(a).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandScope.admin(null, correlation, LockSet.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CommandScope.admin(admin, null, LockSet.empty()))
                .isInstanceOf(NullPointerException.class);
    }
}
