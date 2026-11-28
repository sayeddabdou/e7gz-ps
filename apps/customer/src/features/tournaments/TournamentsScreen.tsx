import { useCallback, useEffect, useRef, useState } from "react";
import { ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import type { components } from "@e7gz/api-client";
import { nativeTokens } from "@e7gz/tokens/native";
import { errorCodeOf, type ApiClient } from "../../api";
import { en as t } from "../../i18n/en";
import { allQuarterHourTimes, buildDateOptions, durationOptions, formatTwelveHourTime } from "../booking/selectionOptions";
import { cairoLocalToInstant } from "../booking/time";
import { tournamentErrorMessage } from "./errorMessages";
import { participantStatusText, tournamentStatusText, tournamentTerminalReasonText } from "./tournamentText";

type Activity = components["schemas"]["Activity"];
type Area = components["schemas"]["CustomerArea"];
type Listing = components["schemas"]["CustomerTournamentListing"];
type Detail = components["schemas"]["CustomerTournamentDetail"];
type Venue = components["schemas"]["CustomerVenueCard"];
type VenueDetails = components["schemas"]["CustomerVenueDetails"];
type CreateRequest = components["schemas"]["CustomerTournamentCreateRequest"];
type Confirmation = { kind: "cancel" } | { kind: "remove"; userId: string; username: string };

const activities: Activity[] = ["PLAYSTATION", "BILLIARDS", "PING_PONG"];
const playModes: components["schemas"]["PlayMode"][] = ["SINGLE", "MULTIPLAYER"];

export function TournamentsScreen({ api, focusVersion = 0 }: { api: ApiClient; focusVersion?: number }) {
  const [page, setPage] = useState<"list" | "create">("list");
  const [detailId, setDetailId] = useState<string>();
  const [refreshVersion, setRefreshVersion] = useState(0);

  if (detailId) {
    return <TournamentDetailScreen api={api} tournamentId={detailId} onBack={() => { setDetailId(undefined); setRefreshVersion((value) => value + 1); }} />;
  }
  if (page === "create") {
    return <CreateTournamentScreen api={api} onBack={() => setPage("list")} onCreated={(id) => { setPage("list"); setDetailId(id); }} />;
  }
  return <TournamentList api={api} focusVersion={focusVersion + refreshVersion} onCreate={() => setPage("create")} onOpen={setDetailId} />;
}

function TournamentList({ api, focusVersion, onCreate, onOpen }: { api: ApiClient; focusVersion: number; onCreate(): void; onOpen(id: string): void }) {
  const [tab, setTab] = useState<"discover" | "mine">("discover");
  const [ended, setEnded] = useState(false);
  const [areas, setAreas] = useState<Area[]>([]);
  const [areaId, setAreaId] = useState<string>();
  const [activity, setActivity] = useState<Activity>("PLAYSTATION");
  const [verified, setVerified] = useState(false);
  const [items, setItems] = useState<Listing[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string>();
  const [inviteOpen, setInviteOpen] = useState(false);
  const [inviteCode, setInviteCode] = useState("");
  const [inviteBusy, setInviteBusy] = useState(false);
  const requestId = useRef(0);

  useEffect(() => {
    const controller = new AbortController();
    void Promise.all([
      api.GET("/api/v1/customer/areas", { signal: controller.signal }),
      api.GET("/api/v1/customer/account-standing", { signal: controller.signal }),
    ]).then(([areaResult, standingResult]) => {
      if (areaResult.data) setAreas(areaResult.data.data);
      if (standingResult.data) setVerified(standingResult.data.data.verifiedPlayer.status === "VERIFIED");
    }).catch(() => undefined);
    return () => controller.abort();
  }, [api, focusVersion]);

  const fetchPage = useCallback(async (cursor?: string) => {
    const currentRequest = ++requestId.current;
    if (cursor) setLoadingMore(true); else { setLoading(true); setError(undefined); }
    try {
      const result = tab === "discover"
        ? await api.GET("/api/v1/customer/tournaments", { params: { query: { activity, ...(areaId ? { areaId } : {}), ...(cursor ? { cursor } : {}), limit: 20 } } })
        : await api.GET("/api/v1/customer/tournaments/mine", { params: { query: { ended, ...(cursor ? { cursor } : {}), limit: 20 } } });
      if (currentRequest !== requestId.current) return;
      if (!result.data) { setError(tournamentErrorMessage(errorCodeOf(result.error))); return; }
      setItems((current) => cursor ? [...current, ...result.data.data.items] : result.data.data.items);
      setNextCursor(result.data.data.nextCursor);
    } catch {
      if (currentRequest === requestId.current) setError(t.tournaments.network);
    } finally {
      if (currentRequest === requestId.current) { setLoading(false); setLoadingMore(false); }
    }
  }, [activity, api, areaId, ended, tab]);

  useEffect(() => { void Promise.resolve().then(() => fetchPage()); }, [fetchPage, focusVersion]);

  const joinPrivate = async () => {
    if (!inviteCode.trim() || inviteBusy) return;
    setInviteBusy(true); setError(undefined);
    try {
      const { data, error: responseError } = await api.POST("/api/v1/customer/tournaments/join-private", {
        body: { inviteCode: inviteCode.trim() },
        params: { header: { "Idempotency-Key": createIdempotencyKey() } },
      });
      if (data) { setInviteOpen(false); setInviteCode(""); onOpen(data.data.tournamentId); }
      else setError(tournamentErrorMessage(errorCodeOf(responseError)));
    } catch { setError(t.tournaments.network); }
    finally { setInviteBusy(false); }
  };

  return (
    <ScrollView contentContainerStyle={styles.content} style={styles.screen}>
      <View style={styles.headingRow}>
        <Text accessibilityRole="header" style={styles.title}>{t.tournaments.title}</Text>
        {verified ? <Button compact label={t.tournaments.create} onPress={onCreate} /> : null}
      </View>
      {!verified ? <Notice>{t.tournaments.createUnavailable}</Notice> : null}
      <View style={styles.segmented}>
        <Chip label={t.tournaments.discover} selected={tab === "discover"} onPress={() => setTab("discover")} />
        <Chip label={t.tournaments.mine} selected={tab === "mine"} onPress={() => setTab("mine")} />
      </View>
      {tab === "discover" ? (
        <>
          <Text style={styles.label}>{t.tournaments.area}</Text>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips}>
            <Chip label={t.tournaments.all} selected={!areaId} onPress={() => setAreaId(undefined)} />
            {areas.map((area) => <Chip key={area.id} label={area.name} selected={area.id === areaId} onPress={() => setAreaId(area.id)} />)}
          </ScrollView>
          <Text style={styles.label}>{t.tournaments.activity}</Text>
          <View style={styles.chips}>{activities.map((value) => <Chip key={value} label={activityText(value)} selected={activity === value} onPress={() => setActivity(value)} />)}</View>
          <Button secondary label={t.tournaments.joinWithCode} onPress={() => setInviteOpen((value) => !value)} />
          {inviteOpen ? (
            <View style={styles.card}>
              <Field label={t.tournaments.inviteCode} value={inviteCode} onChangeText={setInviteCode} autoCapitalize="characters" />
              <Button busy={inviteBusy} label={t.tournaments.join} onPress={joinPrivate} />
            </View>
          ) : null}
        </>
      ) : (
        <View style={styles.segmented}>
          <Chip label={t.tournaments.upcoming} selected={!ended} onPress={() => setEnded(false)} />
          <Chip label={t.tournaments.ended} selected={ended} onPress={() => setEnded(true)} />
        </View>
      )}
      {error ? <ErrorNotice message={error} onRetry={() => void fetchPage()} /> : null}
      {loading ? <ActivityIndicator color={nativeTokens.color.brand.primary} /> : items.length ? items.map((item) => <TournamentCard key={item.tournamentId} item={item} onPress={() => onOpen(item.tournamentId)} />) : <Text style={styles.empty}>{t.tournaments.empty}</Text>}
      {nextCursor ? <Button busy={loadingMore} secondary label={t.tournaments.loadMore} onPress={() => void fetchPage(nextCursor)} /> : null}
    </ScrollView>
  );
}

function TournamentCard({ item, onPress }: { item: Listing; onPress(): void }) {
  const full = item.capacityOccupiedCount >= item.capacity;
  return (
    <Pressable accessibilityRole="button" onPress={onPress} style={({ pressed }) => [styles.card, pressed && styles.pressed]}>
      <View style={styles.headingRow}><Text style={styles.cardTitle}>{item.venueName}</Text><Badge text={full ? t.tournaments.full : tournamentStatusText(item.status)} danger={full} /></View>
      <Text style={styles.body}>{`${activityText(item.activity)}${item.playMode ? ` · ${playModeText(item.playMode)}` : ""}`}</Text>
      <Text style={styles.muted}>{formatInstant(item.startAt)}</Text>
      <Text style={styles.body}>{`${item.capacityOccupiedCount}/${item.capacity} ${t.tournaments.participants}`}</Text>
      {item.myParticipantStatus ? <Text style={styles.brandText}>{participantStatusText(item.myParticipantStatus)}</Text> : null}
    </Pressable>
  );
}

function TournamentDetailScreen({ api, tournamentId, onBack }: { api: ApiClient; tournamentId: string; onBack(): void }) {
  const [detail, setDetail] = useState<Detail>();
  const [serverTime, setServerTime] = useState<string>();
  const [error, setError] = useState<string>();
  const [notice, setNotice] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [version, setVersion] = useState(0);
  const [confirmation, setConfirmation] = useState<Confirmation>();
  const actionKeys = useRef(new Map<string, string>());

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/customer/tournaments/{tournamentId}", { params: { path: { tournamentId } } }).then(({ data, error: responseError }) => {
      if (!active) return;
      if (data) { setDetail(data.data); setServerTime(data.meta.serverTime); setError(undefined); }
      else setError(tournamentErrorMessage(errorCodeOf(responseError)));
    }).catch(() => { if (active) setError(t.tournaments.network); });
    return () => { active = false; };
  }, [api, tournamentId, version]);

  const action = async (kind: "join" | "leave" | "cancel" | "remove", userId?: string) => {
    if (busy) return;
    setBusy(true); setError(undefined); setNotice(undefined);
    const intent = `${kind}:${userId ?? ""}`;
    const key = actionKeys.current.get(intent) ?? createIdempotencyKey();
    actionKeys.current.set(intent, key);
    const params = { path: { tournamentId, ...(userId ? { userId } : {}) }, header: { "Idempotency-Key": key } };
    try {
      const result = kind === "join"
        ? await api.POST("/api/v1/customer/tournaments/{tournamentId}/join", { params: { path: { tournamentId }, header: params.header } })
        : kind === "leave"
          ? await api.POST("/api/v1/customer/tournaments/{tournamentId}/leave", { params: { path: { tournamentId }, header: params.header } })
          : kind === "cancel"
            ? await api.POST("/api/v1/customer/tournaments/{tournamentId}/cancel", { params: { path: { tournamentId }, header: params.header } })
            : await api.POST("/api/v1/customer/tournaments/{tournamentId}/participants/{userId}/remove", { params: { path: { tournamentId, userId: userId! }, header: params.header } });
      if (result.data) {
        actionKeys.current.delete(intent);
        setNotice(kind === "join" ? t.tournaments.joinedNotice : kind === "leave" ? t.tournaments.leftNotice : kind === "cancel" ? t.tournaments.cancelledNotice : undefined);
        setVersion((value) => value + 1);
      } else {
        actionKeys.current.delete(intent);
        setError(tournamentErrorMessage(errorCodeOf(result.error)));
      }
    } catch { setError(t.tournaments.network); }
    finally { setBusy(false); }
  };

  if (!detail || !serverTime) return <View style={styles.center}><Button secondary label={t.tournaments.back} onPress={onBack} />{error ? <ErrorNotice message={error} onRetry={() => setVersion((value) => value + 1)} /> : <ActivityIndicator />}</View>;
  const joined = detail.myParticipantStatus === "JOINED";
  const joinable = !joined && detail.myParticipantStatus !== "INELIGIBLE" && ["WAITING_FOR_PLAYERS", "CONFIRMED"].includes(detail.status) && detail.capacityOccupiedCount < detail.capacity;
  const canLeave = joined && ["WAITING_FOR_PLAYERS", "CONFIRMED"].includes(detail.status);
  const canCancel = detail.createdByMe && detail.hostType === "PLAYER" && ["PENDING_VENUE_APPROVAL", "WAITING_FOR_PLAYERS"].includes(detail.status) && Date.parse(serverTime) < Date.parse(detail.checkpointAt);
  const canRemove = detail.createdByMe && ["WAITING_FOR_PLAYERS", "CONFIRMED"].includes(detail.status) && Date.parse(serverTime) < Date.parse(detail.startAt);
  const terminal = tournamentTerminalReasonText(detail.terminalReason);

  const confirmAction = () => {
    const pending = confirmation;
    setConfirmation(undefined);
    if (!pending) return;
    void action(pending.kind, pending.kind === "remove" ? pending.userId : undefined);
  };

  return (
    <ScrollView contentContainerStyle={styles.content} style={styles.screen}>
      <Button secondary compact label={t.tournaments.back} onPress={onBack} />
      <View style={styles.headingRow}><Text style={styles.title}>{detail.venueName}</Text><Badge text={tournamentStatusText(detail.status)} danger={Boolean(detail.terminalReason)} /></View>
      {terminal ? <Notice danger>{terminal}</Notice> : null}
      {notice ? <Notice>{notice}</Notice> : null}
      {error ? <ErrorNotice message={error} onRetry={() => setVersion((value) => value + 1)} /> : null}
      <View style={styles.card}>
        <Fact label={t.tournaments.activity} value={activityText(detail.activity)} />
        {detail.playMode ? <Fact label={t.tournaments.playMode} value={playModeText(detail.playMode)} /> : null}
        <Fact label={t.tournaments.starts} value={formatInstant(detail.startAt)} />
        <Fact label={t.tournaments.ends} value={formatInstant(detail.endAt)} />
        <Fact label={t.tournaments.checkpoint} value={formatInstant(detail.checkpointAt)} />
        {detail.approvalExpiresAt ? <Fact label={t.tournaments.approvalDeadline} value={<ServerCountdown serverTime={serverTime} target={detail.approvalExpiresAt} />} /> : null}
        <Fact label={t.tournaments.participants} value={`${detail.capacityOccupiedCount}/${detail.capacity}`} />
        <Fact label={t.tournaments.entryFeeLabel} value={formatMoney(detail.entryFee)} />
        <Fact label={t.tournaments.prizeLabel} value={formatMoney(detail.prize)} />
        <Fact label={t.tournaments.roomList} value={detail.rooms.map((room) => room.name).join(", ")} />
        {detail.myParticipantStatus ? <Fact label={t.tournaments.status} value={participantStatusText(detail.myParticipantStatus)} /> : null}
        {detail.inviteCode ? <Fact label={t.tournaments.inviteCodeLabel} value={detail.inviteCode} /> : null}
      </View>
      <Notice>{t.tournaments.moneyNotice}</Notice>
      {joinable ? <Button busy={busy} label={t.tournaments.join} onPress={() => void action("join")} /> : null}
      {canLeave ? <Button busy={busy} secondary label={t.tournaments.leave} onPress={() => void action("leave")} /> : null}
      {canCancel ? <Button busy={busy} danger label={t.tournaments.cancel} onPress={() => setConfirmation({ kind: "cancel" })} /> : null}
      {detail.participants ? <View style={styles.card}><Text style={styles.sectionTitle}>{t.tournaments.participants}</Text>{detail.participants.map((participant) => <View key={participant.userId} style={styles.participant}><View style={styles.flex}><Text style={styles.body}>{participant.username}</Text><Text style={styles.muted}>{participantStatusText(participant.status)}</Text></View>{canRemove && participant.status === "JOINED" ? <Button compact secondary label={t.tournaments.remove} onPress={() => setConfirmation({ kind: "remove", userId: participant.userId, username: participant.username })} /> : null}</View>)}</View> : null}
      <Modal animationType="fade" onRequestClose={() => setConfirmation(undefined)} transparent visible={Boolean(confirmation)}>
        <View style={styles.modalBackdrop}>
          <View accessibilityRole="alert" style={styles.modalCard}>
            <Text style={styles.sectionTitle}>{confirmation?.kind === "remove" ? t.tournaments.removeConfirmTitle : t.tournaments.cancelConfirmTitle}</Text>
            <Text style={styles.body}>{confirmation?.kind === "remove" ? t.tournaments.removeConfirmMessage(confirmation.username) : t.tournaments.cancelConfirmMessage}</Text>
            <View style={styles.modalActions}>
              <Button secondary label={t.tournaments.keepTournament} onPress={() => setConfirmation(undefined)} />
              <Button danger label={confirmation?.kind === "remove" ? t.tournaments.confirmRemove : t.tournaments.confirmCancel} onPress={confirmAction} />
            </View>
          </View>
        </View>
      </Modal>
    </ScrollView>
  );
}

function CreateTournamentScreen({ api, onBack, onCreated }: { api: ApiClient; onBack(): void; onCreated(id: string): void }) {
  const [areas, setAreas] = useState<Area[]>([]);
  const [areaId, setAreaId] = useState<string>();
  const [activity, setActivity] = useState<Activity>("PLAYSTATION");
  const [venues, setVenues] = useState<Venue[]>([]);
  const [venue, setVenue] = useState<Venue>();
  const [details, setDetails] = useState<VenueDetails>();
  const [resourceIds, setResourceIds] = useState<string[]>([]);
  const [playMode, setPlayMode] = useState<components["schemas"]["PlayMode"]>("SINGLE");
  const [capacity, setCapacity] = useState("8");
  const [serverTime, setServerTime] = useState<string>();
  const [date, setDate] = useState("");
  const [startTime, setStartTime] = useState("");
  const [durationMinutes, setDurationMinutes] = useState(60);
  const [visibility, setVisibility] = useState<components["schemas"]["TournamentVisibility"]>("PUBLIC");
  const [participates, setParticipates] = useState(true);
  const [entryFee, setEntryFee] = useState("");
  const [prize, setPrize] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const retryIntent = useRef<{ fingerprint: string; key: string } | undefined>(undefined);

  useEffect(() => {
    const controller = new AbortController();
    void api.GET("/api/v1/customer/areas", { signal: controller.signal }).then(({ data }) => {
      if (data) { setAreas(data.data); setServerTime(data.meta.serverTime); }
    });
    return () => controller.abort();
  }, [api]);
  useEffect(() => {
    if (!areaId) return;
    const controller = new AbortController();
    void api.GET("/api/v1/customer/venues", { signal: controller.signal, params: { query: { areaId, activity, limit: 100 } } }).then(({ data }) => { if (data) setVenues(data.data.items); });
    return () => controller.abort();
  }, [activity, api, areaId]);
  useEffect(() => {
    if (!venue) return;
    const controller = new AbortController();
    void api.GET("/api/v1/customer/venues/{venueId}", { signal: controller.signal, params: { path: { venueId: venue.id }, query: { activity } } }).then(({ data }) => { if (data) setDetails(data.data); });
    return () => controller.abort();
  }, [activity, api, venue]);

  const selectArea = (id: string) => { setAreaId(id); setVenue(undefined); setDetails(undefined); setResourceIds([]); setVenues([]); };
  const selectActivity = (value: Activity) => { setActivity(value); setVenue(undefined); setDetails(undefined); setResourceIds([]); setVenues([]); };
  const selectVenue = (value: Venue) => { setVenue(value); setDetails(undefined); setResourceIds([]); };

  const submit = async () => {
    const numericCapacity = Number(capacity);
    const startAt = cairoLocalToInstant(date, startTime);
    const invalidMoney = (value: string) => value.trim() !== "" && (!Number.isFinite(Number(value)) || Number(value) < 0);
    if (!venue || resourceIds.length < 1 || resourceIds.length > 4 || !Number.isInteger(numericCapacity) || numericCapacity < 8 || numericCapacity > 32 || !startAt || invalidMoney(entryFee) || invalidMoney(prize)) { setError(t.tournaments.validation); return; }
    setBusy(true); setError(undefined);
    const money = (value: string) => value.trim() ? { amount: Number(value).toFixed(2), currency: "EGP" as const } : null;
    const body: CreateRequest = { venueId: venue.id, activity, ...(activity === "PLAYSTATION" ? { playMode } : {}), resourceIds, capacity: numericCapacity, startAt, durationMinutes, creatorParticipates: participates, visibility, entryFee: money(entryFee), prize: money(prize) };
    const fingerprint = JSON.stringify(body);
    const key = retryIntent.current?.fingerprint === fingerprint
      ? retryIntent.current.key
      : createIdempotencyKey();
    retryIntent.current = { fingerprint, key };
    try {
      const { data, error: responseError } = await api.POST("/api/v1/customer/tournaments", { body, params: { header: { "Idempotency-Key": key } } });
      if (data) { retryIntent.current = undefined; onCreated(data.data.tournamentId); }
      else { retryIntent.current = undefined; setError(tournamentErrorMessage(errorCodeOf(responseError))); }
    } catch { setError(t.tournaments.network); }
    finally { setBusy(false); }
  };

  const toggleResource = (id: string) => setResourceIds((current) => current.includes(id) ? current.filter((value) => value !== id) : current.length < 4 ? [...current, id] : current);
  const dateOptions = serverTime ? buildDateOptions(new Date(serverTime)) : [];
  return (
    <ScrollView contentContainerStyle={styles.content} style={styles.screen}>
      <Button secondary compact label={t.tournaments.back} onPress={onBack} />
      <Text style={styles.title}>{t.tournaments.create}</Text>
      {error ? <ErrorNotice message={error} onRetry={submit} /> : null}
      <Text style={styles.label}>{t.tournaments.area}</Text><View style={styles.chips}>{areas.map((area) => <Chip key={area.id} label={area.name} selected={areaId === area.id} onPress={() => selectArea(area.id)} />)}</View>
      <Text style={styles.label}>{t.tournaments.activity}</Text><View style={styles.chips}>{activities.map((value) => <Chip key={value} label={activityText(value)} selected={activity === value} onPress={() => selectActivity(value)} />)}</View>
      <Text style={styles.label}>{t.tournaments.venue}</Text><View style={styles.chips}>{venues.map((value) => <Chip key={value.id} label={value.name} selected={venue?.id === value.id} onPress={() => selectVenue(value)} />)}</View>
      {details ? <><Text style={styles.label}>{t.tournaments.resources}</Text><View style={styles.chips}>{details.resources.map((resource) => <Chip key={resource.id} label={resource.name} selected={resourceIds.includes(resource.id)} onPress={() => toggleResource(resource.id)} />)}</View></> : null}
      {activity === "PLAYSTATION" ? <><Text style={styles.label}>{t.tournaments.playMode}</Text><View style={styles.chips}>{playModes.map((mode) => <Chip key={mode} label={playModeText(mode)} selected={playMode === mode} onPress={() => setPlayMode(mode)} />)}</View></> : null}
      <Field keyboardType="number-pad" label={t.tournaments.capacity} value={capacity} onChangeText={setCapacity} />
      <Text style={styles.label}>{t.tournaments.date}</Text>
      <ScrollView horizontal contentContainerStyle={styles.dateChoices} showsHorizontalScrollIndicator={false}>
        {dateOptions.map((option) => <DateChoice key={option.value} {...option} selected={date === option.value} onPress={() => { setDate(option.value); setStartTime(""); }} />)}
      </ScrollView>
      <Text style={styles.label}>{t.tournaments.startTime}</Text>
      <View accessibilityLabel={t.tournaments.startTime} style={styles.timeChoices}>
        {allQuarterHourTimes.map((value) => <Chip key={value} label={formatTwelveHourTime(value)} selected={startTime === value} onPress={() => setStartTime(value)} />)}
      </View>
      <Text style={styles.label}>{t.tournaments.duration}</Text>
      <ScrollView horizontal contentContainerStyle={styles.chips} showsHorizontalScrollIndicator={false}>
        {durationOptions.map((value) => <Chip key={value} label={t.booking.durationValue(value)} selected={durationMinutes === value} onPress={() => setDurationMinutes(value)} />)}
      </ScrollView>
      <Text style={styles.label}>{t.tournaments.visibility}</Text><View style={styles.chips}><Chip label={t.tournaments.public} selected={visibility === "PUBLIC"} onPress={() => setVisibility("PUBLIC")} /><Chip label={t.tournaments.private} selected={visibility === "PRIVATE"} onPress={() => setVisibility("PRIVATE")} /></View>
      <Text style={styles.label}>{t.tournaments.organizerRole}</Text><View style={styles.chips}><Chip label={t.tournaments.organizerAndParticipant} selected={participates} onPress={() => setParticipates(true)} /><Chip label={t.tournaments.organizerOnly} selected={!participates} onPress={() => setParticipates(false)} /></View>
      <Field keyboardType="decimal-pad" label={t.tournaments.entryFee} value={entryFee} onChangeText={setEntryFee} />
      <Field keyboardType="decimal-pad" label={t.tournaments.prize} value={prize} onChangeText={setPrize} />
      <Notice>{t.tournaments.moneyNotice}</Notice>
      <Button busy={busy} label={t.tournaments.submit} onPress={() => void submit()} />
    </ScrollView>
  );
}

function ServerCountdown({ serverTime, target }: { serverTime: string; target: string }) {
  const anchor = useRef({ server: Date.parse(serverTime), monotonic: monotonicNow() });
  const [remaining, setRemaining] = useState(() => Math.max(0, Date.parse(target) - Date.parse(serverTime)));
  useEffect(() => {
    anchor.current = { server: Date.parse(serverTime), monotonic: monotonicNow() };
    const update = () => setRemaining(Math.max(0, Date.parse(target) - (anchor.current.server + monotonicNow() - anchor.current.monotonic)));
    update(); const interval = setInterval(update, 1_000); return () => clearInterval(interval);
  }, [serverTime, target]);
  const seconds = Math.ceil(remaining / 1_000); return <Text>{`${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`}</Text>;
}

function monotonicNow() { return globalThis.performance.now(); }
function activityText(value: Activity) { return t.tournaments.activities[value]; }
function playModeText(value: components["schemas"]["PlayMode"]) { return t.tournaments.playModes[value]; }
function formatInstant(value: string) { return new Intl.DateTimeFormat("en-EG", { dateStyle: "medium", timeStyle: "short", timeZone: "Africa/Cairo" }).format(new Date(value)); }
function formatMoney(value: components["schemas"]["Money"]) { return Number(value.amount) === 0 ? t.tournaments.free : `${value.amount} ${value.currency}`; }
function createIdempotencyKey(): string {
  if (typeof globalThis.crypto?.randomUUID === "function") return globalThis.crypto.randomUUID();
  const bytes = new Uint8Array(16); if (typeof globalThis.crypto?.getRandomValues !== "function") throw new Error("Secure randomness is unavailable"); globalThis.crypto.getRandomValues(bytes); bytes[6] = (bytes[6]! & 0x0f) | 0x40; bytes[8] = (bytes[8]! & 0x3f) | 0x80; const hex = [...bytes].map((value) => value.toString(16).padStart(2, "0")).join(""); return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function Button({ label, onPress, busy, secondary, danger, compact }: { label: string; onPress(): void; busy?: boolean; secondary?: boolean; danger?: boolean; compact?: boolean }) { return <Pressable accessibilityRole="button" disabled={busy} onPress={onPress} style={({ pressed }) => [styles.button, secondary && styles.secondaryButton, danger && styles.dangerButton, compact && styles.compactButton, (pressed || busy) && styles.pressed]}><Text style={[styles.buttonText, secondary && styles.secondaryButtonText]}>{label}</Text></Pressable>; }
function Chip({ label, selected, onPress }: { label: string; selected: boolean; onPress(): void }) { return <Pressable accessibilityRole="button" accessibilityState={{ selected }} onPress={onPress} style={[styles.chip, selected && styles.selectedChip]}><Text style={[styles.chipText, selected && styles.selectedChipText]}>{label}</Text></Pressable>; }
function DateChoice({ accessibilityLabel, day, month, selected, weekday, onPress }: { accessibilityLabel: string; day: string; month: string; selected: boolean; weekday: string; onPress(): void }) { return <Pressable accessibilityLabel={accessibilityLabel} accessibilityRole="button" accessibilityState={{ selected }} onPress={onPress} style={[styles.dateChoice, selected && styles.dateChoiceSelected]}><Text style={[styles.dateWeekday, selected && styles.dateChoiceSelectedText]}>{weekday}</Text><Text style={[styles.dateDay, selected && styles.dateChoiceSelectedText]}>{day}</Text><Text style={[styles.dateMonth, selected && styles.dateChoiceSelectedText]}>{month}</Text></Pressable>; }
function Field(props: React.ComponentProps<typeof TextInput> & { label: string }) { const { label, ...input } = props; return <View style={styles.field}><Text style={styles.label}>{label}</Text><TextInput accessibilityLabel={label} placeholderTextColor={nativeTokens.color.text.subtle} style={styles.input} {...input} /></View>; }
function Badge({ text, danger }: { text: string; danger?: boolean }) { return <View style={[styles.badge, danger && styles.dangerBadge]}><Text style={[styles.badgeText, danger && styles.dangerText]}>{text}</Text></View>; }
function Notice({ children, danger }: { children: React.ReactNode; danger?: boolean }) { return <View style={[styles.notice, danger && styles.dangerNotice]}><Text style={danger ? styles.dangerText : styles.body}>{children}</Text></View>; }
function ErrorNotice({ message, onRetry }: { message: string; onRetry(): void }) { return <View accessibilityRole="alert" style={[styles.notice, styles.dangerNotice]}><Text style={styles.dangerText}>{message}</Text><Button compact secondary label={t.tournaments.retry} onPress={onRetry} /></View>; }
function Fact({ label, value }: { label: string; value: React.ReactNode }) { return <View style={styles.fact}><Text style={styles.muted}>{label}</Text><Text style={styles.factValue}>{value}</Text></View>; }

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: nativeTokens.color.surface.canvas },
  content: { gap: nativeTokens.spacing["2xl"], padding: nativeTokens.spacing["4xl"], paddingBottom: nativeTokens.spacing["11xl"] },
  center: { flex: 1, gap: nativeTokens.spacing["2xl"], justifyContent: "center", padding: nativeTokens.spacing["4xl"] },
  title: { color: nativeTokens.color.text.customer, flexShrink: 1, fontSize: nativeTokens.typography.fontSize.displaySmall, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  sectionTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.titleSmall, fontWeight: nativeTokens.typography.fontWeight.bold },
  headingRow: { alignItems: "center", flexDirection: "row", gap: nativeTokens.spacing.md, justifyContent: "space-between" },
  segmented: { flexDirection: "row", gap: nativeTokens.spacing.md },
  chips: { flexDirection: "row", flexWrap: "wrap", gap: nativeTokens.spacing.md },
  dateChoices: { flexDirection: "row", gap: nativeTokens.spacing.md, paddingBottom: nativeTokens.spacing.xs },
  dateChoice: { alignItems: "center", backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 76, minWidth: 68, paddingHorizontal: nativeTokens.spacing.lg, paddingVertical: nativeTokens.spacing.md },
  dateChoiceSelected: { backgroundColor: nativeTokens.color.brand.primary, borderColor: nativeTokens.color.brand.primary },
  dateChoiceSelectedText: { color: nativeTokens.color.text.inverse },
  dateWeekday: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.caption, fontWeight: nativeTokens.typography.fontWeight.bold },
  dateDay: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.titleSmall, fontWeight: nativeTokens.typography.fontWeight.bold },
  dateMonth: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.caption },
  timeChoices: { flexDirection: "row", flexWrap: "wrap", gap: nativeTokens.spacing.md },
  chip: { backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, paddingHorizontal: nativeTokens.spacing["2xl"], paddingVertical: nativeTokens.spacing.lg },
  selectedChip: { backgroundColor: nativeTokens.color.surface.info, borderColor: nativeTokens.color.brand.primary },
  chipText: { color: nativeTokens.color.text.customer, fontWeight: nativeTokens.typography.fontWeight.semibold },
  selectedChipText: { color: nativeTokens.color.brand.primary },
  card: { backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["3xl"] },
  cardTitle: { color: nativeTokens.color.text.customer, flex: 1, fontSize: nativeTokens.typography.fontSize.titleSmall, fontWeight: nativeTokens.typography.fontWeight.bold },
  label: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.label, fontWeight: nativeTokens.typography.fontWeight.semibold },
  body: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.body },
  muted: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.bodySmall },
  brandText: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  empty: { color: nativeTokens.color.text.muted, paddingVertical: nativeTokens.spacing["7xl"], textAlign: "center" },
  field: { gap: nativeTokens.spacing.sm },
  input: { backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"], borderWidth: nativeTokens.spacing.hairline, color: nativeTokens.color.text.customer, minHeight: 52, paddingHorizontal: nativeTokens.spacing["2xl"] },
  button: { alignItems: "center", backgroundColor: nativeTokens.color.brand.primary, borderRadius: nativeTokens.radius["2xl"], justifyContent: "center", minHeight: 50, paddingHorizontal: nativeTokens.spacing["2xl"] },
  secondaryButton: { backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderWidth: nativeTokens.spacing.hairline },
  dangerButton: { backgroundColor: nativeTokens.color.feedback.danger },
  compactButton: { alignSelf: "flex-start", minHeight: 40 },
  buttonText: { color: nativeTokens.color.text.inverse, fontWeight: nativeTokens.typography.fontWeight.bold },
  secondaryButtonText: { color: nativeTokens.color.brand.primary },
  pressed: { opacity: 0.65 },
  badge: { backgroundColor: nativeTokens.color.surface.info, borderRadius: nativeTokens.radius.xl, paddingHorizontal: nativeTokens.spacing.md, paddingVertical: nativeTokens.spacing.xs },
  badgeText: { color: nativeTokens.color.brand.primary, fontSize: nativeTokens.typography.fontSize.caption, fontWeight: nativeTokens.typography.fontWeight.bold },
  dangerBadge: { backgroundColor: nativeTokens.color.feedback.dangerSurface },
  dangerText: { color: nativeTokens.color.feedback.danger },
  notice: { backgroundColor: nativeTokens.color.feedback.infoSurface, borderRadius: nativeTokens.radius["2xl"], gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["2xl"] },
  dangerNotice: { backgroundColor: nativeTokens.color.feedback.dangerSurface },
  fact: { alignItems: "flex-start", borderBottomColor: nativeTokens.color.border.subtle, borderBottomWidth: nativeTokens.spacing.hairline, flexDirection: "row", gap: nativeTokens.spacing.md, justifyContent: "space-between", paddingVertical: nativeTokens.spacing.md },
  factValue: { color: nativeTokens.color.text.customer, flexShrink: 1, fontWeight: nativeTokens.typography.fontWeight.semibold, textAlign: "right" },
  participant: { alignItems: "center", flexDirection: "row", gap: nativeTokens.spacing.md },
  flex: { flex: 1 },
  modalBackdrop: { alignItems: "center", backgroundColor: nativeTokens.color.surface.canvas, flex: 1, justifyContent: "center", padding: nativeTokens.spacing["4xl"] },
  modalCard: { backgroundColor: nativeTokens.color.surface.raised, borderRadius: nativeTokens.radius["3xl"], gap: nativeTokens.spacing["2xl"], maxWidth: 520, padding: nativeTokens.spacing["4xl"], width: "100%" },
  modalActions: { flexDirection: "row", gap: nativeTokens.spacing.md, justifyContent: "flex-end" },
});
