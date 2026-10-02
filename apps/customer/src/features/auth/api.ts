import { createApiClient, type components } from "@e7gz/api-client";
import type { TokenPair, TokenStore } from "../../auth/tokenStore";
import type { AppConfig } from "../../config";
import { errorCodeOf } from "../../api";
import type { ApiErrorCode } from "./errorMessages";

type RegisterRequest = components["schemas"]["CustomerRegisterRequest"];
type LoginRequest = components["schemas"]["CustomerLoginRequest"];

export type AuthResult<T> =
  | { ok: true; value: T; serverTime: string }
  | { ok: false; code?: ApiErrorCode; serverTime?: string };

export interface CustomerAuthApi {
  register(request: RegisterRequest): Promise<AuthResult<{ phone: string; challengeId: string }>>;
  login(request: LoginRequest): Promise<AuthResult<TokenPair>>;
  verifyPhone(challengeId: string, code: string): Promise<AuthResult<TokenPair>>;
  resend(phone: string): Promise<AuthResult<{ challengeId: string }>>;
  refresh(refreshToken: string): Promise<TokenPair | null>;
  logout(): Promise<void>;
}

function codeFrom(body: unknown): ApiErrorCode | undefined {
  return errorCodeOf(body) as ApiErrorCode | undefined;
}

export function createCustomerAuthApi(config: AppConfig, tokenStore: TokenStore): CustomerAuthApi {
  const client = createApiClient({ baseUrl: config.apiBaseUrl });
  return {
    async register(request) {
      const { data, error } = await client.POST("/api/v1/customer/auth/register", { body: request });
      if (data) return { ok: true, value: { phone: request.phone, challengeId: data.data.otpChallengeId }, serverTime: data.meta.serverTime };
      return { ok: false, code: codeFrom(error), serverTime: error?.meta.serverTime };
    },
    async login(request) {
      const { data, error } = await client.POST("/api/v1/customer/auth/login", { body: request });
      if (data) return { ok: true, value: data.data, serverTime: data.meta.serverTime };
      return { ok: false, code: codeFrom(error), serverTime: error?.meta.serverTime };
    },
    async verifyPhone(challengeId, code) {
      const { data, error } = await client.POST("/api/v1/customer/auth/verify-phone", { body: { otpChallengeId: challengeId, code } });
      if (data) return { ok: true, value: data.data, serverTime: data.meta.serverTime };
      return { ok: false, code: codeFrom(error), serverTime: error?.meta.serverTime };
    },
    async resend(phone) {
      const { data, error } = await client.POST("/api/v1/customer/auth/otp/send", { body: { phone } });
      if (data) return { ok: true, value: { challengeId: data.data.otpChallengeId }, serverTime: data.meta.serverTime };
      return { ok: false, code: codeFrom(error), serverTime: error?.meta.serverTime };
    },
    async refresh(refreshToken) {
      const { data } = await client.POST("/api/v1/customer/auth/refresh", { body: { refreshToken } });
      return data?.data ?? null;
    },
    async logout() {
      const tokens = await tokenStore.read();
      try {
        if (tokens) await client.POST("/api/v1/customer/auth/logout", { body: { refreshToken: tokens.refreshToken } });
      } finally {
        await tokenStore.clear();
      }
    },
  };
}

export function resendDeadline(serverTime: string): string {
  return new Date(Date.parse(serverTime) + 60_000).toISOString();
}

export function serverClockOffset(serverTime: string, deviceNow: number): number {
  return Date.parse(serverTime) - deviceNow;
}

export function secondsUntil(deadline: string, deviceNow: number, serverOffset = 0): number {
  return Math.max(0, Math.ceil((Date.parse(deadline) - (deviceNow + serverOffset)) / 1000));
}
