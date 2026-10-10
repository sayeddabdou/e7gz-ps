import type { ConfigContext, ExpoConfig } from "expo/config";

export default ({ config }: ConfigContext): ExpoConfig => {
  const googleMapsApiKey = process.env.GOOGLE_MAPS_API_KEY;
  const googleServicesFile = process.env.GOOGLE_SERVICES_JSON;
  const plugins = [...(config.plugins ?? [])];
  if (!plugins.some((plugin) => plugin === "expo-notifications" || (Array.isArray(plugin) && plugin[0] === "expo-notifications"))) {
    plugins.push(["expo-notifications", { defaultChannel: "booking-updates" }]);
  }
  return {
    ...config,
    name: config.name ?? "E7GZ PS",
    slug: config.slug ?? "e7gz-ps",
    plugins,
    ...(googleMapsApiKey || googleServicesFile ? {
      android: {
        ...config.android,
        ...(googleMapsApiKey ? { config: { googleMaps: { apiKey: googleMapsApiKey } } } : {}),
        ...(googleServicesFile ? { googleServicesFile } : {}),
      },
    } : {}),
  };
};
