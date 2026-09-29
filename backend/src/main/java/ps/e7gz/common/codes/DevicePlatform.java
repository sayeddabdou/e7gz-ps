package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Platform of a push device token - {@code ck_device_token_platform}. */
@Schema(enumAsRef = true)
public enum DevicePlatform {
    IOS,
    ANDROID,
    WEB
}
