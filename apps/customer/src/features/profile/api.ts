import type { components } from "@e7gz/api-client";
import { errorCodeOf, type ApiClient } from "../../api";

export type CustomerProfile = components["schemas"]["CustomerProfile"];
export type ProfileErrorCode = components["schemas"]["ApiErrorCode"];

export type ProfileResult<T> =
  | { ok: true; value: T; serverTime?: string }
  | { ok: false; code?: ProfileErrorCode; serverTime?: string };

export interface CustomerProfileApi {
  getProfile(): Promise<ProfileResult<CustomerProfile>>;
  updateFullName(fullName: string): Promise<ProfileResult<CustomerProfile>>;
  changeUsername(username: string): Promise<ProfileResult<CustomerProfile>>;
  requestPhoneChange(phone: string): Promise<ProfileResult<{ challengeId: string }>>;
  verifyPhoneChange(challengeId: string, code: string): Promise<ProfileResult<CustomerProfile>>;
  requestPasswordReset(phone: string): Promise<ProfileResult<{ challengeId: string }>>;
  completePasswordReset(challengeId: string, code: string, password: string): Promise<ProfileResult<void>>;
}

function failure(error: unknown): ProfileResult<never> {
  const envelope = error as { meta?: { serverTime?: string } } | undefined;
  return {
    ok: false,
    code: errorCodeOf(error) as ProfileErrorCode | undefined,
    serverTime: envelope?.meta?.serverTime,
  };
}

export function createCustomerProfileApi(client: ApiClient): CustomerProfileApi {
  return {
    async getProfile() {
      const { data, error } = await client.GET("/api/v1/customer/profile");
      return data
        ? { ok: true, value: data.data, serverTime: data.meta.serverTime }
        : failure(error);
    },
    async updateFullName(fullName) {
      const { data, error } = await client.PATCH("/api/v1/customer/profile", { body: { fullName } });
      return data
        ? { ok: true, value: data.data, serverTime: data.meta.serverTime }
        : failure(error);
    },
    async changeUsername(username) {
      const { data, error } = await client.POST("/api/v1/customer/profile/username", { body: { username } });
      return data
        ? { ok: true, value: data.data, serverTime: data.meta.serverTime }
        : failure(error);
    },
    async requestPhoneChange(phone) {
      const { data, error } = await client.POST("/api/v1/customer/profile/phone", { body: { phone } });
      return data
        ? { ok: true, value: { challengeId: data.data.otpChallengeId }, serverTime: data.meta.serverTime }
        : failure(error);
    },
    async verifyPhoneChange(challengeId, code) {
      const { data, error } = await client.POST("/api/v1/customer/profile/phone/verify", {
        body: { otpChallengeId: challengeId, code },
      });
      return data
        ? { ok: true, value: data.data, serverTime: data.meta.serverTime }
        : failure(error);
    },
    async requestPasswordReset(phone) {
      const { data, error } = await client.POST("/api/v1/customer/auth/password-reset/request", { body: { phone } });
      return data
        ? { ok: true, value: { challengeId: data.data.otpChallengeId }, serverTime: data.meta.serverTime }
        : failure(error);
    },
    async completePasswordReset(challengeId, code, password) {
      const { error, response } = await client.POST("/api/v1/customer/auth/password-reset/complete", {
        body: { otpChallengeId: challengeId, code, password },
      });
      return response.ok ? { ok: true, value: undefined } : failure(error);
    },
  };
}
