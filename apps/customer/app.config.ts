import type { ConfigContext, ExpoConfig } from "expo/config";

export default ({ config }: ConfigContext): ExpoConfig => {
  const googleMapsApiKey = process.env.EXPO_PUBLIC_GOOGLE_MAPS_API_KEY;
  return {
    ...config,
    name: config.name ?? "E7GZ PS",
    slug: config.slug ?? "e7gz-ps",
    ...(googleMapsApiKey ? {
      android: {
        ...config.android,
        config: { googleMaps: { apiKey: googleMapsApiKey } },
      },
    } : {}),
  };
};
