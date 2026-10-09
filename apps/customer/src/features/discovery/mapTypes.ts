import type { components } from "@e7gz/api-client";

export type VenueMarker = components["schemas"]["CustomerVenueMarker"];

export const defaultMapBounds = {
  minLng: 31.20,
  minLat: 29.95,
  maxLng: 31.45,
  maxLat: 30.15,
} as const;

export type MapBounds = {
  minLng: number;
  minLat: number;
  maxLng: number;
  maxLat: number;
};

export type DiscoveryMapProps = {
  markers: VenueMarker[];
  bounds: MapBounds;
  onBoundsChange(bounds: MapBounds): void;
  onViewVenue(venueId: string): void;
};

export function boundsQuery(bounds: MapBounds) {
  return [bounds.minLng, bounds.minLat, bounds.maxLng, bounds.maxLat].join(",");
}

export function boundsAround(lat: number, lng: number): MapBounds {
  const latitudeSpan = defaultMapBounds.maxLat - defaultMapBounds.minLat;
  const longitudeSpan = defaultMapBounds.maxLng - defaultMapBounds.minLng;
  return {
    minLng: lng - longitudeSpan / 2,
    minLat: lat - latitudeSpan / 2,
    maxLng: lng + longitudeSpan / 2,
    maxLat: lat + latitudeSpan / 2,
  };
}

export function boundsDiffer(left: MapBounds, right: MapBounds, tolerance = 0.0005) {
  return Math.abs(left.minLng - right.minLng) > tolerance
    || Math.abs(left.minLat - right.minLat) > tolerance
    || Math.abs(left.maxLng - right.maxLng) > tolerance
    || Math.abs(left.maxLat - right.maxLat) > tolerance;
}
