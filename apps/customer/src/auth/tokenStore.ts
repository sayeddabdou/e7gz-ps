import * as SecureStore from "expo-secure-store";
import { Platform } from "react-native";

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
export function createSecureTokenStore(options: { platform?: typeof Platform.OS; development?: boolean } = {}): TokenStore {
  let webTokens: TokenPair | null = null;
  const useDevelopmentWebMemory = (options.platform ?? Platform.OS) === "web" && (options.development ?? process.env.NODE_ENV !== "production");
  const clear = async () => {
    if (useDevelopmentWebMemory) {
      webTokens = null;
      return;
    }
    if (!(await SecureStore.isAvailableAsync())) return;
    await Promise.all([
      SecureStore.deleteItemAsync(accessTokenKey),
      SecureStore.deleteItemAsync(refreshTokenKey),
    ]);
  };

  return {
    async read() {
      if (useDevelopmentWebMemory) return webTokens;
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
      if (useDevelopmentWebMemory) {
        webTokens = tokens;
        return;
      }
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
