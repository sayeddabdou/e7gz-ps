import { useEffect, useState } from "react";
import { ActivityIndicator, AppState, Image, Linking, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from "react-native";
import type { components } from "@e7gz/api-client";
import { nativeLogicalSpacing, nativeTokens } from "@e7gz/tokens/native";
import { errorCodeOf, type ApiClient } from "../../api";
import { en as t } from "../../i18n/en";
import { BookingFlow } from "../booking";
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
  focusVersion = 0,
  onBack,
}: {
  api: ApiClient;
  venueId: string;
  activity: Activity;
  coordinates?: Coordinates | undefined;
  focusVersion?: number;
  onBack(): void;
}) {
  const [activity, setActivity] = useState<Activity>(initialActivity);
  const [details, setDetails] = useState<Details>();
  const [error, setError] = useState<string>();
  const [version, setVersion] = useState(0);
  const [bookingOpen, setBookingOpen] = useState(false);
  const [bookingResourceId, setBookingResourceId] = useState<string>();
  const [serverTime, setServerTime] = useState<string>();
  const [refreshing, setRefreshing] = useState(false);

  useEffect(() => {
    const subscription = AppState.addEventListener("change", (nextState) => {
      if (nextState === "active") setVersion((value) => value + 1);
    });
    return () => subscription.remove();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const query = { activity, ...(coordinates ? { lat: coordinates.lat, lng: coordinates.lng } : {}) };
    void api.GET("/api/v1/customer/venues/{venueId}", {
      params: { path: { venueId }, query },
      signal: controller.signal,
    }).then(({ data, error: body }) => {
      if (data && data.data.selectedActivity === activity && data.data.activities.includes(activity)) {
        setDetails(data.data);
        setServerTime(data.meta.serverTime);
      }
      else if (data) setError(t.discovery.errors.activityMismatch);
      else setError(discoveryErrorMessage(errorCodeOf(body)));
    }).catch(() => {
      if (!controller.signal.aborted) setError(t.discovery.errors.network);
    });
    return () => controller.abort();
  }, [activity, api, coordinates, focusVersion, venueId, version]);

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

  if (bookingOpen) {
    return <BookingFlow api={api} initialActivity={activity} initialDetails={details} initialResourceId={bookingResourceId} onBack={() => setBookingOpen(false)} referenceDate={serverTime ? new Date(serverTime) : undefined} />;
  }

  const nearest = formatInstant(details.nearestAvailableAt);
  const distance = formatDistance(details.distanceMeters);
  const opensAt = formatInstantTime(details.opensAt);
  const locationUrl = `https://www.openstreetmap.org/?mlat=${details.location.lat}&mlon=${details.location.lng}#map=17/${details.location.lat}/${details.location.lng}`;

  return (
    <ScrollView contentContainerStyle={styles.page} refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); setVersion((value) => value + 1); setTimeout(() => setRefreshing(false), 400); }} title={t.discovery.refreshing} />}>
      <View style={styles.heroShell}>
        {details.imageUrls.length ? (
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.gallery}>
            {details.imageUrls.map((url) => <Image accessibilityLabel={t.discovery.detailPhoto(details.name)} key={url} testID="venue-photo" source={{ uri: url }} style={styles.hero} />)}
          </ScrollView>
        ) : <View style={styles.heroPlaceholder}><Text style={styles.placeholder}>{t.discovery.galleryPlaceholder}</Text></View>}
        <Pressable accessibilityRole="button" accessibilityLabel={t.discovery.back} onPress={onBack} style={styles.backButton}>
          <Text style={styles.backIcon}>‹</Text>
        </Pressable>
      </View>

      <Text accessibilityRole="header" style={styles.title}>{details.name}</Text>
      <Text style={styles.muted}>{[details.area.name, details.address, distance].filter(Boolean).join(" · ")}</Text>
      <View style={styles.ratingPill}><Text style={styles.rating}>{details.averageRating === null ? t.discovery.noRating : `${details.averageRating} ★ · ${t.discovery.reviews(details.reviewCount)}`}</Text></View>
      <View style={styles.metaActions}>
        <View style={styles.statusRow}>
          <View style={details.openNow ? styles.openDot : styles.closedDot} />
          <Text style={details.openNow ? styles.open : styles.closed}>{details.openNow ? t.discovery.openNow : t.discovery.closedNow}</Text>
          {!details.openNow && opensAt ? <Text style={styles.muted}>{t.discovery.opensAt(opensAt)}</Text> : null}
        </View>
        <Pressable accessibilityRole="link" onPress={() => void Linking.openURL(locationUrl)} style={styles.locationButton}><Text style={styles.link}>{t.discovery.viewLocation}</Text></Pressable>
      </View>
      {nearest ? <Text style={styles.available}>{t.discovery.nearest(nearest)}</Text> : <Text style={styles.muted}>{t.discovery.noAvailability}</Text>}
      <View style={styles.phoneNotice}><Text style={styles.phoneNoticeText}>{t.discovery.phoneAfterBooking}</Text></View>

      <Text style={styles.sectionTitle}>{t.discovery.roomsTitle(t.discovery.activities[activity])}</Text>
      {details.resources.length ? details.resources.map((resource) => (
        <View key={resource.id} style={styles.card}>
          <View style={styles.roomTitleRow}>
            <Text style={styles.cardTitle}>{resource.name}</Text>
            {resource.consoleType ? <View style={styles.consoleBadge}><Text style={styles.consoleBadgeText}>{resource.consoleType}</Text></View> : null}
          </View>
          <Text style={styles.roomPriceLine}>
            {details.prices
              .filter((price) => price.consoleType === resource.consoleType)
              .map((price) => t.discovery.hourlyRate(price.playMode ? t.discovery.playModes[price.playMode] : t.discovery.hourlyPrice, formatMoney(price.hourlyPrice) ?? ""))
              .join(" · ") || t.discovery.priceUnavailable}
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityState={{ disabled: !details.bookingEnabled }}
            disabled={!details.bookingEnabled}
            onPress={() => { setBookingResourceId(resource.id); setBookingOpen(true); }}
            style={[styles.roomAction, !details.bookingEnabled && styles.disabledButton]}
          >
            <Text style={details.bookingEnabled ? styles.roomActionText : styles.primaryButtonText}>{details.bookingEnabled ? t.discovery.viewAvailability : t.discovery.bookingUnavailable}</Text>
          </Pressable>
        </View>
      )) : <Text style={styles.muted}>{t.discovery.noResources}</Text>}

      <View style={styles.reviewsCard}>
        <View style={styles.reviewIcon}><Text style={styles.reviewIconText}>★</Text></View>
        <View style={styles.reviewCopy}>
          <Text style={styles.cardTitle}>{t.discovery.customerReviews}</Text>
          <Text style={styles.muted}>{details.averageRating === null ? t.discovery.noRating : `${details.averageRating} ★ · ${t.discovery.reviews(details.reviewCount)}`}</Text>
        </View>
      </View>

      {details.activities.some((value) => value !== activity) ? (
        <>
          <Text style={styles.alsoLabel}>{t.discovery.alsoAvailable}</Text>
          <ScrollView horizontal contentContainerStyle={styles.chips} showsHorizontalScrollIndicator={false}>
            {details.activities.filter((value) => value !== activity).map((value) => (
              <Pressable key={value} accessibilityRole="button" onPress={() => { setDetails(undefined); setError(undefined); setActivity(value); }} style={styles.chip}>
                <Text style={styles.chipText}>{t.discovery.activities[value]}</Text>
                <Text style={styles.chipArrow}>›</Text>
              </Pressable>
            ))}
          </ScrollView>
        </>
      ) : null}

      <Text style={styles.sectionTitle}>{t.discovery.openingHoursTitle}</Text>
      {details.openingHours.map((day) => (
        <Text key={day.weekday} style={styles.hours}>
          {day.closed
            ? `${t.discovery.weekdays[day.weekday]}: ${t.discovery.closed}`
            : `${t.discovery.weekdays[day.weekday]}: ${formatLocalTime(day.opensAtLocal)}–${formatLocalTime(day.closesAtLocal)}`}
        </Text>
      ))}

    </ScrollView>
  );
}

const styles = StyleSheet.create({
  page: { backgroundColor: nativeTokens.color.surface.raised, gap: nativeTokens.spacing.xl, paddingBottom: nativeTokens.spacing["10xl"], paddingTop: nativeTokens.spacing["2xl"], ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["5xl"]) },
  center: { alignItems: "center", backgroundColor: nativeTokens.color.surface.raised, flex: 1, gap: nativeTokens.spacing.lg, justifyContent: "center", ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["5xl"]) },
  heroShell: { position: "relative" },
  backButton: { alignItems: "center", backgroundColor: nativeTokens.color.surface.raised, borderRadius: nativeTokens.radius["2xl"], height: 40, justifyContent: "center", left: nativeTokens.spacing["2xl"], position: "absolute", top: nativeTokens.spacing["2xl"], width: 40, ...nativeTokens.shadow.raised },
  backIcon: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.displaySmall, lineHeight: 30 },
  gallery: { gap: nativeTokens.spacing.md },
  hero: { borderRadius: nativeTokens.radius["5xl"], height: 230, width: 340 },
  heroPlaceholder: { alignItems: "center", backgroundColor: nativeTokens.color.surface.customerSubtle, borderRadius: nativeTokens.radius["5xl"], height: 230, justifyContent: "center" },
  placeholder: { color: nativeTokens.color.text.subtle, fontSize: nativeTokens.typography.fontSize.bodySmall },
  title: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.heading, fontWeight: nativeTokens.typography.fontWeight.extrabold, marginTop: nativeTokens.spacing.sm },
  muted: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.bodySmall },
  ratingPill: { alignSelf: "flex-start", backgroundColor: nativeTokens.color.surface.canvas, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius.xl, borderWidth: nativeTokens.spacing.hairline, paddingHorizontal: nativeTokens.spacing.xl, paddingVertical: nativeTokens.spacing.md },
  rating: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodySmall, fontWeight: nativeTokens.typography.fontWeight.bold },
  metaActions: { alignItems: "center", flexDirection: "row", flexWrap: "wrap", gap: nativeTokens.spacing.lg },
  statusRow: { alignItems: "center", backgroundColor: nativeTokens.color.surface.customerSubtle, borderRadius: nativeTokens.radius.xl, flexDirection: "row", gap: nativeTokens.spacing.md, minHeight: 34, paddingHorizontal: nativeTokens.spacing.xl },
  locationButton: { alignItems: "center", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius.xl, borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 34, paddingHorizontal: nativeTokens.spacing.xl },
  openDot: { backgroundColor: nativeTokens.color.feedback.success, borderRadius: nativeTokens.radius.round, height: 8, width: 8 },
  closedDot: { backgroundColor: nativeTokens.color.feedback.warning, borderRadius: nativeTokens.radius.round, height: 8, width: 8 },
  open: { color: nativeTokens.color.feedback.success, fontWeight: nativeTokens.typography.fontWeight.bold },
  closed: { color: nativeTokens.color.feedback.warning, fontWeight: nativeTokens.typography.fontWeight.bold },
  available: { color: nativeTokens.color.feedback.success, fontWeight: nativeTokens.typography.fontWeight.semibold },
  phoneNotice: { backgroundColor: nativeTokens.color.surface.canvas, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"], borderStyle: "dashed", borderWidth: nativeTokens.spacing.hairline, padding: nativeTokens.spacing["3xl"] },
  phoneNoticeText: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.label, lineHeight: 18 },
  sectionTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.title, fontWeight: nativeTokens.typography.fontWeight.extrabold, marginTop: nativeTokens.spacing["5xl"] },
  chips: { gap: nativeTokens.spacing.md },
  chip: { alignItems: "center", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"], borderWidth: nativeTokens.spacing.hairline, flexDirection: "row", gap: nativeTokens.spacing.md, paddingHorizontal: nativeTokens.spacing["3xl"], paddingVertical: nativeTokens.spacing.xl },
  chipSelected: { backgroundColor: nativeTokens.color.surface.info, borderColor: nativeTokens.color.brand.primary },
  chipText: { color: nativeTokens.color.text.customer, fontWeight: nativeTokens.typography.fontWeight.semibold },
  chipArrow: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  chipTextSelected: { color: nativeTokens.color.brand.primary },
  card: { backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["4xl"], borderWidth: nativeTokens.spacing.hairline, minHeight: 58, padding: nativeTokens.spacing["3xl"], ...nativeTokens.shadow.card },
  roomTitleRow: { alignItems: "center", flexDirection: "row", gap: nativeTokens.spacing.md },
  consoleBadge: { backgroundColor: nativeTokens.color.text.customer, borderRadius: nativeTokens.radius.md, paddingHorizontal: nativeTokens.spacing.md, paddingVertical: nativeTokens.spacing.xxs },
  consoleBadgeText: { color: nativeTokens.color.text.inverse, fontSize: nativeTokens.typography.fontSize.caption, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  roomPriceLine: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.label, marginTop: nativeTokens.spacing.sm },
  roomAction: { alignItems: "center", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", marginTop: nativeTokens.spacing["3xl"], minHeight: 44 },
  roomActionText: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodySmall, fontWeight: nativeTokens.typography.fontWeight.bold },
  cardTitle: { color: nativeTokens.color.text.customer, fontWeight: nativeTokens.typography.fontWeight.bold },
  reviewsCard: { alignItems: "center", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["4xl"], borderWidth: nativeTokens.spacing.hairline, flexDirection: "row", gap: nativeTokens.spacing.xl, marginTop: nativeTokens.spacing["5xl"], padding: nativeTokens.spacing["3xl"] },
  reviewIcon: { alignItems: "center", backgroundColor: nativeTokens.color.feedback.warningSurface, borderRadius: nativeTokens.radius["2xl"], height: 40, justifyContent: "center", width: 40 },
  reviewIconText: { color: nativeTokens.color.feedback.warning, fontSize: nativeTokens.typography.fontSize.title },
  reviewCopy: { flex: 1, gap: nativeTokens.spacing.xs },
  alsoLabel: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.label, fontWeight: nativeTokens.typography.fontWeight.bold, marginTop: nativeTokens.spacing["3xl"] },
  hours: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.bodySmall },
  secondaryButton: { alignItems: "center", backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 50 },
  link: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  disabledButton: { backgroundColor: nativeTokens.color.text.subtle },
  primaryButtonText: { color: nativeTokens.color.text.inverse, fontWeight: nativeTokens.typography.fontWeight.bold },
  error: { color: nativeTokens.color.feedback.danger, textAlign: "center" },
});
