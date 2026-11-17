import type { components } from "@e7gz/api-client";
import { errorCodeOf, type ApiClient } from "../../api";

export type CustomerStanding = components["schemas"]["CustomerAccountStanding"];
export type CustomerPoints = components["schemas"]["CustomerPoints"];
export type CustomerReferrals = components["schemas"]["CustomerReferrals"];
export type CustomerWallet = components["schemas"]["CustomerWallet"];

export type RewardsErrorCode = "VALIDATION_FAILED" | "UNAUTHENTICATED" | "FORBIDDEN" | "RATE_LIMITED";
export type RewardsResult<T> =
  | { ok: true; value: T; serverTime: string }
  | { ok: false; code?: RewardsErrorCode };

export interface CustomerRewardsApi {
  getStanding(): Promise<RewardsResult<CustomerStanding>>;
  getPoints(cursor?: string): Promise<RewardsResult<CustomerPoints>>;
  getReferrals(cursor?: string): Promise<RewardsResult<CustomerReferrals>>;
  getWallet(cursor?: string): Promise<RewardsResult<CustomerWallet>>;
}

export function createCustomerRewardsApi(api: ApiClient): CustomerRewardsApi {
  return {
    async getStanding() {
      const { data, error } = await api.GET("/api/v1/customer/account-standing");
      return data
        ? { ok: true, value: data.data, serverTime: data.meta.serverTime }
        : { ok: false, code: errorCodeOf(error) as RewardsErrorCode | undefined };
    },
    async getPoints(cursor) {
      const { data, error } = await api.GET("/api/v1/customer/points", {
        params: { query: { limit: 20, ...(cursor ? { cursor } : {}) } },
      });
      return data
        ? { ok: true, value: data.data, serverTime: data.meta.serverTime }
        : { ok: false, code: errorCodeOf(error) as RewardsErrorCode | undefined };
    },
    async getReferrals(cursor) {
      const { data, error } = await api.GET("/api/v1/customer/referrals", {
        params: { query: { limit: 20, ...(cursor ? { cursor } : {}) } },
      });
      return data
        ? { ok: true, value: data.data, serverTime: data.meta.serverTime }
        : { ok: false, code: errorCodeOf(error) as RewardsErrorCode | undefined };
    },
    async getWallet(cursor) {
      const { data, error } = await api.GET("/api/v1/customer/wallet", {
        params: { query: { limit: 20, ...(cursor ? { cursor } : {}) } },
      });
      return data
        ? { ok: true, value: data.data, serverTime: data.meta.serverTime }
        : { ok: false, code: errorCodeOf(error) as RewardsErrorCode | undefined };
    },
  };
}
