import { useEffect, useMemo, useRef, useState } from "react";
import { ActivityIndicator, AppState, Image, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from "react-native";
import type { components } from "@e7gz/api-client";
import { nativeLogicalSpacing, nativeTokens } from "@e7gz/tokens/native";
import { errorCodeOf, type ApiClient } from "../../api";
import { en as t } from "../../i18n/en";
import { discoveryErrorMessage } from "./errorMessages";
import { DiscoveryMap } from "./DiscoveryMap";
import { formatDistance, formatInstant, formatMoney } from "./format";
import { expoLocationAccess, type LocationAccess, type LocationResult } from "./location";
import { boundsAround, boundsDiffer, boundsQuery, defaultMapBounds, type MapBounds, type VenueMarker } from "./mapTypes";
import { VenueDetails } from "./VenueDetails";
import { FindAvailableScreen } from "./FindAvailableScreen";

type Activity = components["schemas"]["Activity"];
type CustomerArea = components["schemas"]["CustomerArea"];
type VenueCard = components["schemas"]["CustomerVenueCard"];
type ListState =
  | { status: "idle" | "loading" }
  | { status: "error"; message: string }
  | { status: "success"; venues: VenueCard[]; nextCursor: string | null; loadingMore: boolean };
type MapState =
  | { status: "idle" | "loading"; markers: VenueMarker[] }
  | { status: "error"; message: string; markers: VenueMarker[] }
  | { status: "success"; markers: VenueMarker[] };

const activities: Activity[] = ["PLAYSTATION", "BILLIARDS", "PING_PONG"];

export function DiscoveryScreen({
  api,
  location = expoLocationAccess,
  focusVersion = 0,
}: {
  api: ApiClient;
  location?: LocationAccess;
  focusVersion?: number;
}) {
  const [locationResult, setLocationResult] = useState<LocationResult>();
  const [manual, setManual] = useState(false);
  const [areas, setAreas] = useState<CustomerArea[]>();
  const [serverTime, setServerTime] = useState<string>();
  const [areasError, setAreasError] = useState<string>();
  const [areasVersion, setAreasVersion] = useState(0);
  const [selectedArea, setSelectedArea] = useState<CustomerArea>();
  const [activity, setActivity] = useState<Activity>("PLAYSTATION");
  const [state, setState] = useState<ListState>({ status: "idle" });
  const [requestVersion, setRequestVersion] = useState(0);
  const [viewMode, setViewMode] = useState<"list" | "map">("list");
  const [mapBounds, setMapBounds] = useState<MapBounds>(defaultMapBounds);
  const [mapState, setMapState] = useState<MapState>({ status: "idle", markers: [] });
  const [mapRequestVersion, setMapRequestVersion] = useState(0);
  const [venueId, setVenueId] = useState<string>();
  const [findingAvailable, setFindingAvailable] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const centeredOnLocation = useRef(false);

  useEffect(() => {
    let active = true;
    void location.current().then((result) => {
      if (!active) return;
      if (result.status === "granted") setState({ status: "loading" });
      setLocationResult(result);
    });
    return () => { active = false; };
  }, [location]);

  useEffect(() => {
    const controller = new AbortController();
    void api.GET("/api/v1/customer/areas", { signal: controller.signal }).then(({ data, error }) => {
      if (data) {
        setAreas(data.data);
        setServerTime(data.meta.serverTime);
      }
      else setAreasError(discoveryErrorMessage(errorCodeOf(error)));
    }).catch(() => {
      if (!controller.signal.aborted) setAreasError(t.discovery.errors.network);
    });
    return () => controller.abort();
  }, [api, areasVersion, focusVersion]);

  const coordinates = locationResult?.status === "granted" ? locationResult.coordinates : undefined;
  const canDiscover = Boolean(coordinates || selectedArea);
  const query = useMemo(() => ({
    activity,
    ...(selectedArea ? { areaId: selectedArea.id } : {}),
    ...(coordinates ? { lat: coordinates.lat, lng: coordinates.lng } : {}),
  }), [activity, coordinates, selectedArea]);

  useEffect(() => {
    if (!coordinates || selectedArea || centeredOnLocation.current) return;
    centeredOnLocation.current = true;
    setMapBounds(boundsAround(coordinates.lat, coordinates.lng));
  }, [coordinates, selectedArea]);

  useEffect(() => {
    const subscription = AppState.addEventListener("change", (nextState) => {
      if (nextState !== "active") return;
      setRequestVersion((version) => version + 1);
      setMapRequestVersion((version) => version + 1);
    });
    return () => subscription.remove();
  }, []);

  useEffect(() => {
    if (!canDiscover) return undefined;
    const controller = new AbortController();
    void api.GET("/api/v1/customer/venues", { params: { query }, signal: controller.signal }).then(({ data, error }) => {
      if (data) setState({ status: "success", venues: data.data.items, nextCursor: data.data.nextCursor, loadingMore: false });
      else setState({ status: "error", message: discoveryErrorMessage(errorCodeOf(error)) });
    }).catch(() => {
      if (!controller.signal.aborted) setState({ status: "error", message: t.discovery.errors.network });
    });
    return () => controller.abort();
  }, [api, canDiscover, focusVersion, query, requestVersion]);

  useEffect(() => {
    if (!canDiscover || viewMode !== "map") return undefined;
    const controller = new AbortController();
    const mapQuery = { bounds: boundsQuery(mapBounds), ...query };
    void api.GET("/api/v1/customer/venues/map", { params: { query: mapQuery }, signal: controller.signal }).then(({ data, error }) => {
      if (data) setMapState({ status: "success", markers: data.data });
      else setMapState((current) => ({ status: "error", message: discoveryErrorMessage(errorCodeOf(error)), markers: current.markers }));
    }).catch(() => {
      if (!controller.signal.aborted) setMapState((current) => ({ status: "error", message: t.discovery.errors.network, markers: current.markers }));
    });
    return () => controller.abort();
  }, [api, canDiscover, focusVersion, mapBounds, mapRequestVersion, query, viewMode]);

  const requestLocation = async () => {
    const result = await location.request();
    if (result.status === "granted") setState({ status: "loading" });
    setLocationResult(result);
  };
  const chooseManual = () => {
    setManual(true);
    setLocationResult((current) => current ?? { status: "denied" });
  };

  const refresh = () => {
    setRefreshing(true);
    setRequestVersion((version) => version + 1);
    setMapRequestVersion((version) => version + 1);
    setTimeout(() => setRefreshing(false), 400);
  };

  const loadMore = async () => {
    if (state.status !== "success" || !state.nextCursor || state.loadingMore) return;
    const before = state;
    setState({ ...state, loadingMore: true });
    try {
      const result = await api.GET("/api/v1/customer/venues", { params: { query: { ...query, cursor: state.nextCursor } } });
      if (!result.data) return setState({ status: "error", message: discoveryErrorMessage(errorCodeOf(result.error)) });
      const venues = mergeVenuePages(before.venues, result.data.data.items);
      const nextCursor = venues.length === before.venues.length && result.data.data.nextCursor === before.nextCursor
        ? null
        : result.data.data.nextCursor;
      setState({
        status: "success",
        venues,
        nextCursor,
        loadingMore: false,
      });
    } catch {
      setState({ status: "error", message: t.discovery.errors.network });
    }
  };

  if (venueId) {
    return <VenueDetails api={api} venueId={venueId} activity={activity} coordinates={coordinates} focusVersion={focusVersion} onBack={() => setVenueId(undefined)} />;
  }

  if (findingAvailable && serverTime) {
    return <FindAvailableScreen api={api} areas={areas ?? []} initialActivity={activity} referenceDate={new Date(serverTime)} onBack={() => setFindingAvailable(false)} onViewVenue={(nextVenueId, nextActivity) => { setActivity(nextActivity); setFindingAvailable(false); setVenueId(nextVenueId); }} />;
  }

  if (!locationResult || (!manual && locationResult.status !== "granted")) {
    return (
      <View style={styles.permissionPage}>
        <View style={styles.locationMark} />
        <Text accessibilityRole="header" style={styles.permissionTitle}>{t.discovery.location.title}</Text>
        <Text style={styles.permissionBody}>{locationResult?.status === "unavailable" ? t.discovery.location.unavailable : t.discovery.location.body}</Text>
        <Pressable accessibilityRole="button" onPress={requestLocation} style={styles.primaryButton}>
          <Text style={styles.primaryButtonText}>{t.discovery.location.allow}</Text>
        </Pressable>
        <Pressable accessibilityRole="button" onPress={chooseManual} style={styles.secondaryButton}>
          <Text style={styles.secondaryButtonText}>{t.discovery.location.manual}</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.page} refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} title={t.discovery.refreshing} />}>
      <Text style={styles.eyebrow}>{t.discovery.ready}</Text>
      <Text accessibilityRole="header" style={styles.title}>{selectedArea?.name ?? t.discovery.currentLocation}</Text>

      <Text style={styles.label}>{t.discovery.area}</Text>
      {!areas && !areasError ? <ActivityIndicator accessibilityLabel={t.loading} /> : null}
      {areasError ? (
        <View accessibilityRole="alert" style={styles.errorBox}>
          <Text style={styles.errorText}>{areasError}</Text>
          <Pressable onPress={() => { setAreasError(undefined); setAreas(undefined); setAreasVersion((version) => version + 1); }}><Text style={styles.linkText}>{t.retry}</Text></Pressable>
        </View>
      ) : null}
      <ScrollView horizontal contentContainerStyle={styles.chips} showsHorizontalScrollIndicator={false}>
        {coordinates ? <FilterChip label={t.discovery.currentLocation} selected={!selectedArea} onPress={() => { setState({ status: "loading" }); setMapState((current) => ({ status: "loading", markers: current.markers })); setSelectedArea(undefined); }} /> : null}
        {areas?.map((area) => <FilterChip key={area.id} label={area.name} selected={selectedArea?.id === area.id} onPress={() => { setState({ status: "loading" }); setMapState((current) => ({ status: "loading", markers: current.markers })); setSelectedArea(area); }} />)}
      </ScrollView>

      <Text style={styles.label}>{t.discovery.activity}</Text>
      <ScrollView horizontal contentContainerStyle={styles.chips} showsHorizontalScrollIndicator={false}>
        {activities.map((value) => <FilterChip key={value} label={t.discovery.activities[value]} selected={activity === value} onPress={() => { setState({ status: "loading" }); setMapState((current) => ({ status: "loading", markers: current.markers })); setActivity(value); }} />)}
      </ScrollView>

      <View accessibilityRole="tablist" style={styles.viewSwitch}>
        <FilterChip label={t.discovery.listView} selected={viewMode === "list"} onPress={() => setViewMode("list")} />
        <FilterChip label={t.discovery.mapView} selected={viewMode === "map"} onPress={() => { setMapState((current) => ({ status: "loading", markers: current.markers })); setViewMode("map"); }} />
      </View>
      <Pressable accessibilityRole="button" disabled={!areas?.length || !serverTime} onPress={() => setFindingAvailable(true)} style={styles.findAvailableButton}><Text style={styles.findAvailableText}>{t.findAvailable.title}</Text></Pressable>

      {!canDiscover ? <Text style={styles.notice}>{t.discovery.chooseArea}</Text> : null}
      {viewMode === "list" && state.status === "loading" ? <View accessibilityRole="progressbar" style={styles.loading}><ActivityIndicator /><Text>{t.loading}</Text></View> : null}
      {viewMode === "list" && state.status === "error" ? (
        <View accessibilityRole="alert" style={styles.errorBox}>
          <Text style={styles.errorText}>{state.message}</Text>
          <Pressable onPress={() => { setState({ status: "loading" }); setRequestVersion((version) => version + 1); }}><Text style={styles.linkText}>{t.retry}</Text></Pressable>
        </View>
      ) : null}
      {viewMode === "list" && state.status === "success" && state.venues.length === 0 ? <Text style={styles.empty}>{t.discovery.empty}</Text> : null}
      {viewMode === "list" && state.status === "success" ? state.venues.map((venue) => (
        <VenueCardView key={venue.id} venue={venue} onPress={() => setVenueId(venue.id)} />
      )) : null}
      {viewMode === "list" && state.status === "success" && state.nextCursor ? (
        <Pressable accessibilityRole="button" disabled={state.loadingMore} onPress={loadMore} style={styles.secondaryButton}>
          <Text style={styles.secondaryButtonText}>{state.loadingMore ? t.loading : t.discovery.loadMore}</Text>
        </Pressable>
      ) : null}
      {viewMode === "map" && canDiscover ? (
        <View style={styles.mapShell}>
          <DiscoveryMap
            bounds={mapBounds}
            markers={mapState.markers}
            onBoundsChange={(nextBounds) => {
              if (boundsDiffer(mapBounds, nextBounds)) {
                setMapState((current) => ({ status: "loading", markers: current.markers }));
                setMapBounds(nextBounds);
              }
            }}
            onViewVenue={setVenueId}
          />
          {mapState.status === "loading" ? <View accessibilityRole="progressbar" style={styles.mapOverlay}><ActivityIndicator /><Text>{t.loading}</Text></View> : null}
          {mapState.status === "error" ? <View accessibilityRole="alert" style={[styles.mapOverlay, styles.mapErrorOverlay]}><Text style={styles.errorText}>{mapState.message}</Text><Pressable onPress={() => { setMapState((current) => ({ status: "loading", markers: current.markers })); setMapRequestVersion((version) => version + 1); }}><Text style={styles.linkText}>{t.retry}</Text></Pressable></View> : null}
          {mapState.status === "success" && mapState.markers.length === 0 ? <View style={styles.mapOverlay}><Text style={styles.empty}>{t.discovery.mapEmpty}</Text></View> : null}
        </View>
      ) : null}
    </ScrollView>
  );
}

function mergeVenuePages(current: VenueCard[], next: VenueCard[]) {
  const venues = new Map(current.map((venue) => [venue.id, venue]));
  next.forEach((venue) => venues.set(venue.id, venue));
  return [...venues.values()];
}

function FilterChip({ label, selected, onPress }: { label: string; selected: boolean; onPress(): void }) {
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ selected }} onPress={onPress} style={[styles.chip, selected && styles.chipSelected]}>
      <Text style={[styles.chipText, selected && styles.chipTextSelected]}>{label}</Text>
    </Pressable>
  );
}

function VenueCardView({ venue, onPress }: { venue: VenueCard; onPress(): void }) {
  const distance = formatDistance(venue.distanceMeters);
  const price = formatMoney(venue.startingPrice);
  const nearest = formatInstant(venue.nearestAvailableAt);
  return (
    <Pressable accessibilityRole="button" onPress={onPress} style={styles.card}>
      {venue.imageUrl
        ? <Image accessibilityLabel={t.discovery.cardPhoto(venue.name)} testID="venue-card-image" source={{ uri: venue.imageUrl }} style={styles.cardImage} />
        : <View style={styles.cardImagePlaceholder}><Text style={styles.placeholderText}>{t.discovery.photoPlaceholder}</Text></View>}
      <View style={styles.cardBody}>
        <Text style={styles.cardTitle}>{venue.name}</Text>
        <Text style={styles.muted}>{[venue.area.name, distance].filter(Boolean).join(" · ")}</Text>
        <Text style={styles.rating}>{venue.averageRating === null ? t.discovery.noRating : `${venue.averageRating} ★ · ${t.discovery.reviews(venue.reviewCount)}`}</Text>
        <Text style={styles.price}>{price ? t.discovery.fromPerHour(price) : t.discovery.priceUnavailable}</Text>
        <Text style={nearest ? styles.available : styles.muted}>{nearest ? t.discovery.nearest(nearest) : t.discovery.noAvailability}</Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  page: { backgroundColor: nativeTokens.color.surface.raised, gap: nativeTokens.spacing.xl, paddingBottom: nativeTokens.spacing["10xl"], ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["5xl"]) },
  permissionPage: { flex: 1, justifyContent: "center", backgroundColor: nativeTokens.color.surface.raised, ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["7xl"]) },
  locationMark: { alignSelf: "center", width: 30, height: 30, borderColor: nativeTokens.color.text.inverse, borderRadius: nativeTokens.radius.round, borderWidth: 4, backgroundColor: nativeTokens.color.brand.primary, marginBottom: nativeTokens.spacing["7xl"], ...nativeTokens.shadow.raised },
  permissionTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.heading, fontWeight: nativeTokens.typography.fontWeight.extrabold, textAlign: "center" },
  permissionBody: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.body, lineHeight: 22, marginVertical: nativeTokens.spacing["7xl"], textAlign: "center" },
  primaryButton: { alignItems: "center", backgroundColor: nativeTokens.color.brand.primary, borderRadius: nativeTokens.radius["3xl"], justifyContent: "center", minHeight: 54 },
  primaryButtonText: { color: nativeTokens.color.text.inverse, fontWeight: nativeTokens.typography.fontWeight.bold },
  secondaryButton: { alignItems: "center", backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 50, marginTop: nativeTokens.spacing.md },
  secondaryButtonText: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  eyebrow: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.bodySmall, marginTop: nativeTokens.spacing["4xl"] },
  title: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.displaySmall, fontWeight: nativeTokens.typography.fontWeight.extrabold, letterSpacing: -0.5 },
  label: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.bodySmall, fontWeight: nativeTokens.typography.fontWeight.semibold, marginTop: nativeTokens.spacing.md },
  chips: { gap: nativeTokens.spacing.md },
  viewSwitch: { flexDirection: "row", gap: nativeTokens.spacing.md },
  chip: { alignItems: "center", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 44, paddingHorizontal: nativeTokens.spacing["4xl"] },
  chipSelected: { backgroundColor: nativeTokens.color.surface.info, borderColor: nativeTokens.color.brand.primary },
  chipText: { color: nativeTokens.color.text.customer, fontWeight: nativeTokens.typography.fontWeight.semibold },
  chipTextSelected: { color: nativeTokens.color.brand.primary },
  notice: { color: nativeTokens.color.text.muted, paddingVertical: nativeTokens.spacing["7xl"], textAlign: "center" },
  loading: { alignItems: "center", gap: nativeTokens.spacing.md, paddingVertical: nativeTokens.spacing["7xl"] },
  errorBox: { backgroundColor: nativeTokens.color.feedback.dangerSurface, borderRadius: nativeTokens.radius["4xl"], gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["3xl"] },
  errorText: { color: nativeTokens.color.feedback.danger },
  linkText: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  empty: { color: nativeTokens.color.text.muted, paddingVertical: nativeTokens.spacing["7xl"], textAlign: "center" },
  card: { backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["4xl"], borderWidth: nativeTokens.spacing.hairline, flexDirection: "row", minHeight: 116, overflow: "hidden", ...nativeTokens.shadow.card },
  cardImage: { width: 104, minHeight: 116 },
  cardImagePlaceholder: { alignItems: "center", backgroundColor: nativeTokens.color.surface.customerSubtle, justifyContent: "center", width: 104 },
  placeholderText: { color: nativeTokens.color.text.subtle, fontSize: nativeTokens.typography.fontSize.caption, textAlign: "center" },
  cardBody: { flex: 1, gap: nativeTokens.spacing.xs, justifyContent: "center", padding: nativeTokens.spacing["2xl"] },
  cardTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodyLarge, fontWeight: nativeTokens.typography.fontWeight.bold },
  muted: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.bodySmall },
  rating: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.label, fontWeight: nativeTokens.typography.fontWeight.semibold },
  price: { color: nativeTokens.color.brand.primary, fontSize: nativeTokens.typography.fontSize.bodySmall, fontWeight: nativeTokens.typography.fontWeight.bold },
  available: { color: nativeTokens.color.feedback.success, fontSize: nativeTokens.typography.fontSize.label, fontWeight: nativeTokens.typography.fontWeight.semibold },
  mapShell: { minHeight: 360, position: "relative" },
  mapOverlay: { alignItems: "center", backgroundColor: nativeTokens.color.surface.raised, borderRadius: nativeTokens.radius["3xl"], gap: nativeTokens.spacing.md, justifyContent: "center", left: nativeTokens.spacing.xl, opacity: 0.92, padding: nativeTokens.spacing.xl, position: "absolute", right: nativeTokens.spacing.xl, top: nativeTokens.spacing.xl },
  mapErrorOverlay: { backgroundColor: nativeTokens.color.feedback.dangerSurface },
  findAvailableButton: { alignItems: "center", borderColor: nativeTokens.color.brand.primary, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 48 },
  findAvailableText: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
});
