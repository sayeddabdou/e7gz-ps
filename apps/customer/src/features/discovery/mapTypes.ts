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
