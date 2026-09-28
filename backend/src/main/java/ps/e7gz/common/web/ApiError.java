package ps.e7gz.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import ps.e7gz.common.error.ApiErrorCode;

/**
 * Documents an error response of an endpoint in the OpenAPI contract: the HTTP status and the {@link ApiErrorCode}s
 * it can carry, with the {@link ErrorResponse} envelope as the body. Status and codes come from arch 09 §1.4–§1.5 and
 * the FR the endpoint implements.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ApiError.List.class)
public @interface ApiError {

    int status();

    ApiErrorCode[] codes();

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        ApiError[] value();
    }
}
