import * as ExpoLocation from "expo-location";

export type Coordinates = { lat: number; lng: number };
export type LocationResult =
  | { status: "granted"; coordinates: Coordinates }
  | { status: "denied" | "undetermined" | "unavailable" };

export interface LocationAccess {
  current(): Promise<LocationResult>;
  request(): Promise<LocationResult>;
}

async function coordinates(): Promise<LocationResult> {
  try {
    const position = await ExpoLocation.getCurrentPositionAsync({ accuracy: ExpoLocation.Accuracy.Balanced });
    return { status: "granted", coordinates: { lat: position.coords.latitude, lng: position.coords.longitude } };
  } catch {
    return { status: "unavailable" };
  }
}

export const expoLocationAccess: LocationAccess = {
  async current() {
    try {
      const permission = await ExpoLocation.getForegroundPermissionsAsync();
      if (permission.granted) return coordinates();
      return { status: permission.canAskAgain ? "undetermined" : "denied" };
    } catch {
      return { status: "unavailable" };
    }
  },
  async request() {
    try {
      const permission = await ExpoLocation.requestForegroundPermissionsAsync();
      return permission.granted ? coordinates() : { status: "denied" };
    } catch {
      return { status: "unavailable" };
    }
  },
};
