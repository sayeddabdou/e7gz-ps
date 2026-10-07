import { useMemo, useState } from "react";
import { Image, Pressable, StyleSheet, Text, View } from "react-native";
import { nativeTokens } from "@e7gz/tokens/native";
import { en as t } from "../../i18n/en";
import { formatDistance, formatInstant, formatMoney } from "./format";
import type { DiscoveryMapProps, VenueMarker } from "./mapTypes";

export function DiscoveryMap({ markers, bounds, onViewVenue }: DiscoveryMapProps) {
  const [selectedId, setSelectedId] = useState<string>();
  const selected = markers.find((marker) => marker.id === selectedId) ?? markers[0];
  const positions = useMemo(() => markers.map((marker) => ({
    marker,
    inline: `${Math.max(4, Math.min(92, ((marker.location.lng - bounds.minLng) / (bounds.maxLng - bounds.minLng)) * 100))}%` as const,
    block: `${Math.max(4, Math.min(88, (1 - ((marker.location.lat - bounds.minLat) / (bounds.maxLat - bounds.minLat))) * 100))}%` as const,
  })), [bounds, markers]);

  return (
    <View accessibilityLabel={t.discovery.mapLabel} style={styles.shell}>
      <View style={styles.mapSurface}>
        {positions.map(({ marker, inline, block }) => (
          <Pressable
            accessibilityLabel={t.discovery.mapMarker(marker.name)}
            accessibilityRole="button"
            key={marker.id}
            onPress={() => setSelectedId(marker.id)}
            style={[styles.marker, { insetInlineStart: inline, top: block }]}
            testID={`map-marker-${marker.id}`}
          >
            <Text style={styles.markerText}>●</Text>
          </Pressable>
        ))}
      </View>
      {selected ? <MarkerPreview marker={selected} onViewVenue={onViewVenue} /> : null}
    </View>
  );
}

function MarkerPreview({ marker, onViewVenue }: { marker: VenueMarker; onViewVenue(venueId: string): void }) {
  const distance = formatDistance(marker.distanceMeters);
  const price = formatMoney(marker.startingPrice);
  const nearest = formatInstant(marker.nearestAvailableAt);
  return (
    <View style={styles.preview} testID={`map-preview-${marker.id}`}>
      {marker.imageUrl
        ? <Image accessibilityLabel={t.discovery.cardPhoto(marker.name)} source={{ uri: marker.imageUrl }} style={styles.photo} testID="map-preview-image" />
        : <View style={styles.photoPlaceholder}><Text style={styles.placeholder}>{t.discovery.photoPlaceholder}</Text></View>}
      <View style={styles.previewBody}>
        <Text style={styles.title}>{marker.name}</Text>
        {distance ? <Text style={styles.muted}>{distance}</Text> : null}
        <Text style={styles.rating}>{marker.averageRating === null ? t.discovery.noRating : `${marker.averageRating} ★ · ${t.discovery.reviews(marker.reviewCount)}`}</Text>
        <Text style={styles.price}>{price ?? t.discovery.priceUnavailable}</Text>
        <Text style={styles.available}>{nearest ? t.discovery.nearest(nearest) : t.discovery.noAvailability}</Text>
        <Pressable accessibilityRole="button" onPress={() => onViewVenue(marker.id)} style={styles.action}>
          <Text style={styles.actionText}>{t.discovery.viewVenue}</Text>
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  shell: { gap: nativeTokens.spacing.xl },
  mapSurface: { backgroundColor: nativeTokens.color.surface.customerSubtle, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["4xl"], borderWidth: nativeTokens.spacing.hairline, height: 360, overflow: "hidden", position: "relative" },
  marker: { alignItems: "center", backgroundColor: nativeTokens.color.brand.primary, borderColor: nativeTokens.color.text.inverse, borderRadius: nativeTokens.radius.round, borderWidth: 3, height: 32, justifyContent: "center", position: "absolute", transform: [{ translateX: -16 }, { translateY: -16 }], width: 32, ...nativeTokens.shadow.raised },
  markerText: { color: nativeTokens.color.text.inverse, fontSize: nativeTokens.typography.fontSize.caption },
  preview: { backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["4xl"], borderWidth: nativeTokens.spacing.hairline, flexDirection: "row", overflow: "hidden", ...nativeTokens.shadow.card },
  photo: { minHeight: 170, width: 112 },
  photoPlaceholder: { alignItems: "center", backgroundColor: nativeTokens.color.surface.customerSubtle, justifyContent: "center", width: 112 },
  placeholder: { color: nativeTokens.color.text.subtle, fontSize: nativeTokens.typography.fontSize.caption, textAlign: "center" },
  previewBody: { flex: 1, gap: nativeTokens.spacing.xs, padding: nativeTokens.spacing["2xl"] },
  title: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodyLarge, fontWeight: nativeTokens.typography.fontWeight.bold },
  muted: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.bodySmall },
  rating: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.label, fontWeight: nativeTokens.typography.fontWeight.semibold },
  price: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  available: { color: nativeTokens.color.feedback.success, fontSize: nativeTokens.typography.fontSize.label, fontWeight: nativeTokens.typography.fontWeight.semibold },
  action: { alignItems: "center", alignSelf: "flex-start", backgroundColor: nativeTokens.color.brand.primary, borderRadius: nativeTokens.radius["2xl"], marginTop: nativeTokens.spacing.md, paddingHorizontal: nativeTokens.spacing["3xl"], paddingVertical: nativeTokens.spacing.lg },
  actionText: { color: nativeTokens.color.text.inverse, fontWeight: nativeTokens.typography.fontWeight.bold },
});
