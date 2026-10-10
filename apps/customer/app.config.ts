import type { ConfigContext, ExpoConfig } from "expo/config";

export default ({ config }: ConfigContext): ExpoConfig => {
  const googleMapsApiKey = process.env.GOOGLE_MAPS_API_KEY;
  const googleServicesFile = process.env.GOOGLE_SERVICES_JSON;
  // iPhone push (architecture A7; PO decision 10 Oct 2026, E7-43): FCM through APNs needs the Firebase iOS app. Its
  // GoogleService-Info.plist and the bundle id come from the PO's Apple Developer and Firebase accounts, set up before
  // deployment (E7-101). Until they are given, the build has no Firebase iOS app and push is simply not offered.
  const googleServiceInfoPlist = process.env.GOOGLE_SERVICE_INFO_PLIST;
  const iosBundleIdentifier = process.env.IOS_BUNDLE_IDENTIFIER;
  const plugins = [...(config.plugins ?? [])];
  if (!plugins.some((plugin) => plugin === "expo-notifications" || (Array.isArray(plugin) && plugin[0] === "expo-notifications"))) {
    plugins.push(["expo-notifications", { defaultChannel: "booking-updates" }]);
  }
  if (googleServiceInfoPlist) {
    plugins.push(
      "@react-native-firebase/app",
      "@react-native-firebase/messaging",
      ["expo-build-properties", { ios: { useFrameworks: "static" } }],
    );
  }
  return {
    ...config,
    name: config.name ?? "E7GZ PS",
    slug: config.slug ?? "e7gz-ps",
    plugins,
    ...(googleServiceInfoPlist || iosBundleIdentifier ? {
      ios: {
        ...config.ios,
        ...(iosBundleIdentifier ? { bundleIdentifier: iosBundleIdentifier } : {}),
        ...(googleServiceInfoPlist ? {
          googleServicesFile: googleServiceInfoPlist,
          infoPlist: { ...config.ios?.infoPlist, UIBackgroundModes: ["remote-notification"] },
        } : {}),
      },
    } : {}),
    ...(googleMapsApiKey || googleServicesFile ? {
      android: {
        ...config.android,
        ...(googleMapsApiKey ? { config: { googleMaps: { apiKey: googleMapsApiKey } } } : {}),
        ...(googleServicesFile ? { googleServicesFile } : {}),
      },
    } : {}),
  };
};
