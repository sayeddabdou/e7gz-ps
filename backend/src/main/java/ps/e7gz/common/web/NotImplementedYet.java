package ps.e7gz.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Contract stubs (the {@code Sx-P0} tasks): the endpoint exists in the contract and answers 501 until the owning task
 * implements it. Clients build against {@code pnpm mock} meanwhile.
 */
public final class NotImplementedYet {

    private NotImplementedYet() {
    }

    public static ResponseStatusException stub() {
        return new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }
}
