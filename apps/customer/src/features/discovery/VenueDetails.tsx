import { useEffect, useState } from "react";
import { ActivityIndicator, Image, Linking, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import type { components } from "@e7gz/api-client";
import { nativeLogicalSpacing, nativeTokens } from "@e7gz/tokens/native";
import { errorCodeOf, type ApiClient } from "../../api";
import { en as t } from "../../i18n/en";
import { discoveryErrorMessage } from "./errorMessages";
import { formatDistance, formatInstant, formatInstantTime, formatLocalTime, formatMoney } from "./format";
import type { Coordinates } from "./location";

type Activity = components["schemas"]["Activity"];
type Details = components["schemas"]["CustomerVenueDetails"];

export function VenueDetails({
  api,
  venueId,
  activity: initialActivity,
  coordinates,
  onBack,
}: {
  api: ApiClient;
  venueId: string;
  activity: Activity;
  coordinates?: Coordinates | undefined;
  onBack(): void;
}) {
  const [activity, setActivity] = useState<Activity>(initialActivity);
  const [details, setDetails] = useState<Details>();
  const [error, setError] = useState<string>();
  const [version, setVersion] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    const query = { activity, ...(coordinates ? { lat: coordinates.lat, lng: coordinates.lng } : {}) };
    void api.GET("/api/v1/customer/venues/{venueId}", {
      params: { path: { venueId }, query },
      signal: controller.signal,
    }).then(({ data, error: body }) => {
      if (data) setDetails(data.data);
      else setError(discoveryErrorMessage(errorCodeOf(body)));
    }).catch(() => {
      if (!controller.signal.aborted) setError(t.discovery.errors.network);
    });
    return () => controller.abort();
  }, [activity, api, coordinates, venueId, version]);

  if (!details && !error) {
    return <View accessibilityRole="progressbar" style={styles.center}><ActivityIndicator /><Text>{t.loading}</Text></View>;
  }
  if (error) {
    return (
      <View accessibilityRole="alert" style={styles.center}>
        <Text style={styles.error}>{error}</Text>
        <Pressable onPress={() => { setError(undefined); setVersion((value) => value + 1); }} style={styles.secondaryButton}><Text style={styles.link}>{t.retry}</Text></Pressable>
        <Pressable onPress={onBack} style={styles.secondaryButton}><Text style={styles.link}>{t.discovery.back}</Text></Pressable>
      </View>
    );
  }
  if (!details) return null;

  const nearest = formatInstant(details.nearestAvailableAt);
  const distance = formatDistance(details.distanceMeters);
  const opensAt = formatInstantTime(details.opensAt);
  const locationUrl = `https://www.openstreetmap.org/?mlat=${details.location.lat}&mlon=${details.location.lng}#map=17/${details.location.lat}/${details.location.lng}`;

  return (
    <ScrollView contentContainerStyle={styles.page}>
      <Pressable accessibilityRole="button" onPress={onBack} style={styles.backButton}><Text style={styles.link}>{t.discovery.back}</Text></Pressable>
      {details.imageUrls.length ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.gallery}>
          {details.imageUrls.map((url) => <Image accessibilityLabel={t.discovery.detailPhoto(details.name)} key={url} testID="venue-photo" source={{ uri: url }} style={styles.hero} />)}
        </ScrollView>
      ) : <View style={styles.heroPlaceholder}><Text style={styles.placeholder}>{t.discovery.galleryPlaceholder}</Text></View>}

      <Text accessibilityRole="header" style={styles.title}>{details.name}</Text>
      <Text style={styles.muted}>{[details.area.name, details.address, distance].filter(Boolean).join(" · ")}</Text>
      <Text style={styles.rating}>{details.averageRating === null ? t.discovery.noRating : `${details.averageRating} ★ · ${t.discovery.reviews(details.reviewCount)}`}</Text>
      <View style={styles.statusRow}>
        <Text style={details.openNow ? styles.open : styles.closed}>{details.openNow ? t.discovery.openNow : t.discovery.closedNow}</Text>
        {!details.openNow && opensAt ? <Text style={styles.muted}>{t.discovery.opensAt(opensAt)}</Text> : null}
      </View>
      {nearest ? <Text style={styles.available}>{t.discovery.nearest(nearest)}</Text> : <Text style={styles.muted}>{t.discovery.noAvailability}</Text>}
      <Pressable accessibilityRole="link" onPress={() => void Linking.openURL(locationUrl)} style={styles.secondaryButton}><Text style={styles.link}>{t.discovery.viewLocation}</Text></Pressable>

      <Text style={styles.sectionTitle}>{t.discovery.activitiesTitle}</Text>
      <ScrollView horizontal contentContainerStyle={styles.chips} showsHorizontalScrollIndicator={false}>
        {details.activities.map((value) => (
          <Pressable key={value} accessibilityRole="button" accessibilityState={{ selected: value === activity }} onPress={() => { setDetails(undefined); setError(undefined); setActivity(value); }} style={[styles.chip, value === activity && styles.chipSelected]}>
            <Text style={[styles.chipText, value === activity && styles.chipTextSelected]}>{t.discovery.activities[value]}</Text>
          </Pressable>
        ))}
      </ScrollView>

      <Text style={styles.sectionTitle}>{t.discovery.resourcesTitle}</Text>
      {details.resources.length ? details.resources.map((resource) => (
        <View key={resource.id} style={styles.card}><Text style={styles.cardTitle}>{[resource.name, resource.consoleType].filter(Boolean).join(" · ")}</Text></View>
      )) : <Text style={styles.muted}>{t.discovery.noResources}</Text>}

      <Text style={styles.sectionTitle}>{t.discovery.pricesTitle}</Text>
      {details.prices.length ? details.prices.map((price, index) => (
        <View key={`${price.consoleType}-${price.playMode}-${index}`} style={styles.priceRow}>
          <Text style={styles.cardTitle}>{[price.consoleType, price.playMode ? t.discovery.playModes[price.playMode] : undefined].filter(Boolean).join(" · ") || t.discovery.hourlyPrice}</Text>
          <Text style={styles.price}>{formatMoney(price.hourlyPrice)}</Text>
        </View>
      )) : <Text style={styles.muted}>{t.discovery.priceUnavailable}</Text>}

      <Text style={styles.sectionTitle}>{t.discovery.openingHoursTitle}</Text>
      {details.openingHours.map((day) => (
        <Text key={day.weekday} style={styles.hours}>
          {day.closed
            ? `${t.discovery.weekdays[day.weekday]}: ${t.discovery.closed}`
            : `${t.discovery.weekdays[day.weekday]}: ${formatLocalTime(day.opensAtLocal)}–${formatLocalTime(day.closesAtLocal)}`}
        </Text>
      ))}

      <Pressable
        accessibilityRole="button"
        accessibilityState={{ disabled: !details.bookingEnabled }}
        disabled={!details.bookingEnabled}
        style={[styles.primaryButton, !details.bookingEnabled && styles.disabledButton]}
      >
        <Text style={styles.primaryButtonText}>{details.bookingEnabled ? t.discovery.continueBooking : t.discovery.bookingUnavailable}</Text>
      </Pressable>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  page: { backgroundColor: nativeTokens.color.surface.raised, gap: nativeTokens.spacing.xl, paddingBottom: nativeTokens.spacing["8xl"], ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["5xl"]) },
  center: { alignItems: "center", backgroundColor: nativeTokens.color.surface.raised, flex: 1, gap: nativeTokens.spacing.lg, justifyContent: "center", ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["5xl"]) },
  backButton: { alignSelf: "flex-start", marginTop: nativeTokens.spacing["3xl"], paddingVertical: nativeTokens.spacing.md },
  gallery: { gap: nativeTokens.spacing.md },
  hero: { borderRadius: nativeTokens.radius["4xl"], height: 220, width: 320 },
  heroPlaceholder: { alignItems: "center", backgroundColor: nativeTokens.color.surface.customerSubtle, borderRadius: nativeTokens.radius["4xl"], height: 220, justifyContent: "center" },
  placeholder: { color: nativeTokens.color.text.subtle, fontSize: nativeTokens.typography.fontSize.bodySmall },
  title: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.heading, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  muted: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.bodySmall },
  rating: { color: nativeTokens.color.text.customer, fontWeight: nativeTokens.typography.fontWeight.bold },
  statusRow: { alignItems: "center", flexDirection: "row", gap: nativeTokens.spacing.md },
  open: { color: nativeTokens.color.feedback.success, fontWeight: nativeTokens.typography.fontWeight.bold },
  closed: { color: nativeTokens.color.feedback.warning, fontWeight: nativeTokens.typography.fontWeight.bold },
  available: { color: nativeTokens.color.feedback.success, fontWeight: nativeTokens.typography.fontWeight.semibold },
  sectionTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.titleSmall, fontWeight: nativeTokens.typography.fontWeight.extrabold, marginTop: nativeTokens.spacing["3xl"] },
  chips: { gap: nativeTokens.spacing.md },
  chip: { borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"], borderWidth: nativeTokens.spacing.hairline, paddingHorizontal: nativeTokens.spacing["3xl"], paddingVertical: nativeTokens.spacing.xl },
  chipSelected: { backgroundColor: nativeTokens.color.surface.info, borderColor: nativeTokens.color.brand.primary },
  chipText: { color: nativeTokens.color.text.customer, fontWeight: nativeTokens.typography.fontWeight.semibold },
  chipTextSelected: { color: nativeTokens.color.brand.primary },
  card: { backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, padding: nativeTokens.spacing["3xl"] },
  cardTitle: { color: nativeTokens.color.text.customer, fontWeight: nativeTokens.typography.fontWeight.bold },
  priceRow: { alignItems: "center", borderBottomColor: nativeTokens.color.border.subtle, borderBottomWidth: nativeTokens.spacing.hairline, flexDirection: "row", justifyContent: "space-between", paddingVertical: nativeTokens.spacing.xl },
  price: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  hours: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.bodySmall },
  secondaryButton: { alignItems: "center", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 48 },
  link: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  primaryButton: { alignItems: "center", backgroundColor: nativeTokens.color.brand.primary, borderRadius: nativeTokens.radius["3xl"], justifyContent: "center", marginTop: nativeTokens.spacing["4xl"], minHeight: 54 },
  disabledButton: { backgroundColor: nativeTokens.color.text.subtle },
  primaryButtonText: { color: nativeTokens.color.text.inverse, fontWeight: nativeTokens.typography.fontWeight.bold },
  error: { color: nativeTokens.color.feedback.danger, textAlign: "center" },
});
