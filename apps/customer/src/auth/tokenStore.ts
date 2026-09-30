import * as SecureStore from "expo-secure-store";

const accessTokenKey = "e7gz.customer.accessToken";
const refreshTokenKey = "e7gz.customer.refreshToken";

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
}

export interface TokenStore {
  read(): Promise<TokenPair | null>;
  write(tokens: TokenPair): Promise<void>;
  clear(): Promise<void>;
}

/** Mobile credentials stay in the platform keychain/keystore, never AsyncStorage. */
export function createSecureTokenStore(): TokenStore {
  const clear = async () => {
    if (!(await SecureStore.isAvailableAsync())) return;
    await Promise.all([
      SecureStore.deleteItemAsync(accessTokenKey),
      SecureStore.deleteItemAsync(refreshTokenKey),
    ]);
  };

  return {
    async read() {
      if (!(await SecureStore.isAvailableAsync())) return null;
      const [accessToken, refreshToken] = await Promise.all([
        SecureStore.getItemAsync(accessTokenKey),
        SecureStore.getItemAsync(refreshTokenKey),
      ]);
      if (accessToken && refreshToken) return { accessToken, refreshToken };
      if (accessToken || refreshToken) await clear();
      return null;
    },
    async write(tokens) {
      if (!(await SecureStore.isAvailableAsync())) {
        throw new Error("Secure token storage is unavailable on this platform.");
      }
      try {
        await SecureStore.setItemAsync(accessTokenKey, tokens.accessToken);
        await SecureStore.setItemAsync(refreshTokenKey, tokens.refreshToken);
      } catch (error) {
        await clear();
        throw error;
      }
    },
    clear,
  };
}
