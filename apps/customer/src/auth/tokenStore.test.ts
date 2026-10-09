import * as SecureStore from "expo-secure-store";
import { createSecureTokenStore } from "./tokenStore";

jest.mock("expo-secure-store", () => ({
  isAvailableAsync: jest.fn(),
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

const secureStore = jest.mocked(SecureStore);

beforeEach(() => {
  jest.clearAllMocks();
  secureStore.isAvailableAsync.mockResolvedValue(true);
});

describe("secure token storage", () => {
  it("writes, reads and clears both tokens through expo-secure-store", async () => {
    secureStore.getItemAsync
      .mockResolvedValueOnce("access-token")
      .mockResolvedValueOnce("refresh-token");
    const store = createSecureTokenStore();

    await store.write({ accessToken: "access-token", refreshToken: "refresh-token" });
    await expect(store.read()).resolves.toEqual({ accessToken: "access-token", refreshToken: "refresh-token" });
    await store.clear();

    expect(secureStore.setItemAsync).toHaveBeenCalledTimes(2);
    expect(secureStore.deleteItemAsync).toHaveBeenCalledTimes(2);
  });

  it("clears an incomplete token pair instead of returning partial credentials", async () => {
    secureStore.getItemAsync.mockResolvedValueOnce("access-token").mockResolvedValueOnce(null);
    const store = createSecureTokenStore();

    await expect(store.read()).resolves.toBeNull();
    expect(secureStore.deleteItemAsync).toHaveBeenCalledTimes(2);
  });

  it("does not fall back to insecure storage when secure storage is unavailable", async () => {
    secureStore.isAvailableAsync.mockResolvedValue(false);
    const store = createSecureTokenStore();

    await expect(store.read()).resolves.toBeNull();
    await expect(store.write({ accessToken: "access-token", refreshToken: "refresh-token" }))
      .rejects.toThrow("Secure token storage is unavailable");
    await store.clear();

    expect(secureStore.getItemAsync).not.toHaveBeenCalled();
    expect(secureStore.setItemAsync).not.toHaveBeenCalled();
    expect(secureStore.deleteItemAsync).not.toHaveBeenCalled();
  });

  it("uses memory only for the development web mock", async () => {
    secureStore.isAvailableAsync.mockResolvedValue(false);
    const store = createSecureTokenStore({ platform: "web", development: true });

    await store.write({ accessToken: "web-access", refreshToken: "web-refresh" });
    await expect(store.read()).resolves.toEqual({ accessToken: "web-access", refreshToken: "web-refresh" });
    await store.clear();
    await expect(store.read()).resolves.toBeNull();
    expect(secureStore.setItemAsync).not.toHaveBeenCalled();
  });
});
