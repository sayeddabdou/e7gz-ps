import { useCallback, useEffect, useRef, useState } from "react";
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import type { components } from "@e7gz/api-client";
import { nativeLogicalSpacing, nativeTokens } from "@e7gz/tokens/native";
import { errorCodeOf, type ApiClient } from "../../api";
import { en as t } from "../../i18n/en";
import { formatMoney } from "../discovery/format";
import { discoveryErrorMessage } from "../discovery/errorMessages";
import { bookingEventText, bookingStatusText } from "../my-bookings/bookingText";
import { bookingErrorMessage } from "./errorMessages";
import { allQuarterHourTimes, buildDateOptions, durationOptions, formatTwelveHourTime, minutesToTime } from "./selectionOptions";
import { cairoLocalToInstant, countdownSeconds, countdownSecondsAt, formatCountdown, serverClockOffset } from "./time";

type Activity = components["schemas"]["Activity"];
type Details = components["schemas"]["CustomerVenueDetails"];
type PlayMode = components["schemas"]["PlayMode"];
type Quote = components["schemas"]["CustomerBookingQuote"];
type Booking = components["schemas"]["CustomerBookingDetail"];
type BookingRequest = components["schemas"]["CustomerBookingCreateRequest"];
type StartOption = { date: string; key: string; time: string };

export function BookingFlow({
  api,
  initialDetails,
  initialActivity,
  initialResourceId,
  onBack,
  onOpenMyBookings,
  onOpenProfile,
  idempotencyKeyFactory = createIdempotencyKey,
  initialServerTime,
  initialPhoneVerified,
  pendingPollIntervalMs = 5_000,
}: {
  api: ApiClient;
  initialDetails: Details;
  initialActivity: Activity;
  initialResourceId?: string | undefined;
  onBack(): void;
  onOpenMyBookings?(): void;
  onOpenProfile?(): void;
  idempotencyKeyFactory?: () => string;
  initialServerTime: string;
  initialPhoneVerified?: boolean;
  pendingPollIntervalMs?: number;
}) {
  const [details, setDetails] = useState(initialDetails);
  const [activity, setActivity] = useState(initialActivity);
  const [resourceId, setResourceId] = useState<string | undefined>(initialResourceId);
  const [playMode, setPlayMode] = useState<PlayMode>("SINGLE");
  const [date, setDate] = useState("");
  const [start, setStart] = useState<StartOption>();
  const [dateOptions] = useState(() => buildDateOptions(new Date(initialServerTime)));
  const [currentServerTime, setCurrentServerTime] = useState(() => Date.parse(initialServerTime));
  const [phoneVerified, setPhoneVerified] = useState<boolean | undefined>(initialPhoneVerified);
  const [profileError, setProfileError] = useState<string>();
  const [durationMinutes, setDurationMinutes] = useState(60);
  const [quote, setQuote] = useState<Quote>();
  const [quoteLoading, setQuoteLoading] = useState(false);
  const [quoteError, setQuoteError] = useState<string>();
  const [dateError, setDateError] = useState<string>();
  const [timeError, setTimeError] = useState<string>();
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [detailsError, setDetailsError] = useState<string>();
  const [submitError, setSubmitError] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const [pending, setPending] = useState<{ booking: Booking; serverTime: string }>();
  const requestRef = useRef<BookingRequest | undefined>(undefined);
  const attemptKeyRef = useRef<string | undefined>(undefined);
  const submittingRef = useRef(false);
  const quoteGenerationRef = useRef(0);
  const detailsGenerationRef = useRef(0);

  useEffect(() => {
    const offset = serverClockOffset(initialServerTime, Date.now());
    const update = () => setCurrentServerTime(Date.now() + offset);
    const interval = setInterval(update, 30_000);
    return () => clearInterval(interval);
  }, [initialServerTime]);

  const loadProfile = useCallback(async () => {
    setProfileError(undefined);
    try {
      const { data, error } = await api.GET("/api/v1/customer/profile");
      if (data) setPhoneVerified(data.data.phoneVerified);
      else setProfileError(bookingErrorMessage(errorCodeOf(error)));
    } catch {
      setProfileError(t.booking.errors.network);
    }
  }, [api]);

  useEffect(() => { if (initialPhoneVerified === undefined) void Promise.resolve().then(loadProfile); }, [initialPhoneVerified, loadProfile]);

  const resetAttempt = () => {
    setQuote(undefined);
    setQuoteError(undefined);
    setDateError(undefined);
    setTimeError(undefined);
    setSubmitError(undefined);
    requestRef.current = undefined;
    attemptKeyRef.current = undefined;
    quoteGenerationRef.current += 1;
  };

  const chooseActivity = async (nextActivity: Activity, force = false) => {
    if (nextActivity === activity && !force) return;
    setActivity(nextActivity);
    setDetailsLoading(true);
    setDetailsError(undefined);
    setResourceId(undefined);
    resetAttempt();
    const generation = ++detailsGenerationRef.current;
    try {
      const { data, error } = await api.GET("/api/v1/customer/venues/{venueId}", {
        params: { path: { venueId: details.id }, query: { activity: nextActivity } },
      });
      if (generation !== detailsGenerationRef.current) return;
      if (data) setDetails(data.data);
      else setDetailsError(discoveryErrorMessage(errorCodeOf(error)));
    } catch {
      if (generation === detailsGenerationRef.current) setDetailsError(t.booking.errors.network);
    } finally {
      if (generation === detailsGenerationRef.current) setDetailsLoading(false);
    }
  };

  const selectedResource = details.resources.find((resource) => resource.id === resourceId);
  const bookingRequest = (): BookingRequest | undefined => {
    const startAt = start ? cairoLocalToInstant(start.date, start.time) : undefined;
    if (!selectedResource || !startAt) return undefined;
    return {
      resourceId: selectedResource.id,
      startAt,
      durationMinutes,
      ...(activity === "PLAYSTATION" ? { playMode } : {}),
    };
  };

  const requestQuote = async () => {
    setQuoteError(undefined);
    setDateError(undefined);
    setTimeError(undefined);
    if (!selectedResource) {
      setQuoteError(t.booking.errors.roomRequired);
      return;
    }
    if (!isCalendarDate(date)) {
      setDateError(t.booking.errors.dateInvalid);
      return;
    }
    if (!start || !isClockTime(start.time)) {
      setTimeError(t.booking.errors.timeInvalid);
      return;
    }
    const request = bookingRequest();
    if (!request) {
      setTimeError(t.booking.errors.timeInvalid);
      return;
    }
    setQuoteLoading(true);
    setQuoteError(undefined);
    const generation = ++quoteGenerationRef.current;
    try {
      const { data, error } = await api.GET("/api/v1/customer/bookings/quote", {
        params: {
          query: {
            resourceId: request.resourceId,
            startAt: request.startAt,
            durationMinutes: request.durationMinutes,
            ...(request.playMode ? { playMode: request.playMode } : {}),
          },
        },
      });
      if (generation !== quoteGenerationRef.current) return;
      if (data) {
        if (data.data.nominalBookedMinutes !== request.durationMinutes) {
          setQuoteError(t.booking.errors.quoteDurationMismatch);
          return;
        }
        requestRef.current = request;
        setQuote(data.data);
      } else {
        setQuoteError(bookingErrorMessage(errorCodeOf(error)));
      }
    } catch {
      if (generation === quoteGenerationRef.current) setQuoteError(t.booking.errors.network);
    } finally {
      if (generation === quoteGenerationRef.current) setQuoteLoading(false);
    }
  };

  const submit = async () => {
    if (submittingRef.current || !requestRef.current) return;
    submittingRef.current = true;
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      const idempotencyKey = attemptKeyRef.current ?? idempotencyKeyFactory();
      attemptKeyRef.current = idempotencyKey;
      const { data, error } = await api.POST("/api/v1/customer/bookings", {
        params: { header: { "Idempotency-Key": idempotencyKey } },
        body: requestRef.current,
      });
      if (data) setPending({ booking: data.data, serverTime: data.meta.serverTime });
      else setSubmitError(bookingErrorMessage(errorCodeOf(error)));
    } catch {
      setSubmitError(t.booking.errors.network);
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  if (pending) return <PendingBooking api={api} booking={pending.booking} onOpenMyBookings={onOpenMyBookings} pollIntervalMs={pendingPollIntervalMs} serverTime={pending.serverTime} />;

  if (quote) {
    return (
      <ScrollView contentContainerStyle={styles.page}>
        <View style={styles.header}>
          <Pressable accessibilityRole="button" accessibilityLabel={t.booking.edit} onPress={() => { setQuote(undefined); setSubmitError(undefined); }} style={styles.backButton}><Text style={styles.backIcon}>‹</Text></Pressable>
          <Text accessibilityRole="header" style={styles.headerTitle}>{t.booking.summaryTitle}</Text>
        </View>
        <View style={styles.summaryCard}>
          <Text style={styles.venue}>{details.name}</Text>
          <SummaryRow label={t.booking.room} value={[selectedResource?.name, selectedResource?.consoleType].filter(Boolean).join(" · ")} />
          <SummaryRow label={t.booking.date} value={start ? formatBookingDate(start.date) : ""} />
          <SummaryRow label={t.booking.startTime} value={start ? formatTwelveHourTime(start.time) : ""} />
          {activity === "PLAYSTATION" ? <SummaryRow label={t.booking.playMode} value={t.discovery.playModes[playMode]} /> : null}
          <SummaryRow label={t.booking.hourlyPrice} value={formatMoney(quote.hourlyPrice) ?? ""} />
          <SummaryRow label={t.booking.duration} value={t.booking.durationValue(quote.nominalBookedMinutes)} />
        </View>
        <View style={styles.summaryCard}>
          <SummaryRow label={t.booking.venuePrice} value={formatMoney(quote.venuePriceTotal) ?? ""} />
          <SummaryRow label={t.booking.bookingFee} value={quote.bookingFee.displayAsFree ? t.booking.free : formatMoney(quote.bookingFee) ?? ""} />
          <SummaryRow emphasis label={t.booking.total} value={formatMoney(quote.totalAmount) ?? ""} />
        </View>
        <Text style={styles.notice}>{t.booking.quoteNotice}</Text>
        {submitError ? <View accessibilityRole="alert" style={styles.errorBox}><Text style={styles.error}>{submitError}</Text></View> : null}
        {!details.bookingEnabled ? <Text accessibilityRole="alert" style={styles.notice}>{t.booking.bookingDisabled}</Text> : null}
        {details.bookingEnabled && !isOnlineIntakeOpen(currentServerTime, details.openingHours, details.openNow) ? <Text accessibilityRole="alert" style={styles.notice}>{t.booking.intakeClosedNotice}</Text> : null}
        <Pressable accessibilityRole="button" accessibilityState={{ disabled: submitting || !details.bookingEnabled }} disabled={submitting || !details.bookingEnabled} onPress={() => void submit()} style={[styles.primaryButton, (submitting || !details.bookingEnabled) && styles.disabledButton]}>
          {submitting ? <ActivityIndicator color={nativeTokens.color.text.inverse} /> : <Text style={styles.primaryButtonText}>{submitError ? t.booking.retryBooking : t.booking.confirm}</Text>}
        </Pressable>
      </ScrollView>
    );
  }

  const venueSession = venueSessionForDate(date, details.openingHours);
  const rawTimeOptions: StartOption[] = venueSession?.closed
    ? []
    : venueSession?.times ?? allQuarterHourTimes.map((timeValue) => ({ date, key: `${date}:${timeValue}`, time: timeValue }));
  const timeOptions = rawTimeOptions.filter((option) => {
    const startAt = cairoLocalToInstant(option.date, option.time);
    if (!startAt || Date.parse(startAt) < currentServerTime + 15 * 60_000) return false;
    return !venueSession || venueSession.closed || !venueSession.endsAt
      ? true
      : Date.parse(startAt) + durationMinutes * 60_000 <= Date.parse(venueSession.endsAt);
  });
  const canQuote = Boolean(selectedResource && date && start && phoneVerified && !detailsLoading && timeOptions.some((option) => option.key === start.key));
  return (
    <ScrollView contentContainerStyle={styles.page}>
      <View style={styles.header}>
        <Pressable accessibilityRole="button" accessibilityLabel={t.discovery.back} onPress={onBack} style={styles.backButton}><Text style={styles.backIcon}>‹</Text></Pressable>
        <View style={styles.headerCopy}>
          <Text accessibilityRole="header" style={styles.headerTitle}>{selectedResource?.name ?? t.booking.title}</Text>
          <Text style={styles.headerSubtitle}>{details.name}</Text>
        </View>
      </View>

      {!initialResourceId ? (
        <>
          <Text style={styles.label}>{t.booking.activity}</Text>
          <ScrollView horizontal contentContainerStyle={styles.chips} showsHorizontalScrollIndicator={false}>
            {initialDetails.activities.map((value) => <Choice key={value} label={t.discovery.activities[value]} selected={value === activity} onPress={() => void chooseActivity(value)} />)}
          </ScrollView>
          {detailsLoading ? <View accessibilityRole="progressbar" style={styles.inlineLoading}><ActivityIndicator /><Text>{t.loading}</Text></View> : null}
          {detailsError ? <View accessibilityRole="alert" style={styles.errorBox}><Text style={styles.error}>{detailsError}</Text><Pressable onPress={() => void chooseActivity(activity, true)}><Text style={styles.link}>{t.retry}</Text></Pressable></View> : null}

          <Text style={styles.label}>{t.booking.resource}</Text>
          {!detailsLoading && !detailsError && details.resources.length ? details.resources.map((resource) => (
            <Choice
              key={resource.id}
              label={[resource.name, resource.consoleType].filter(Boolean).join(" · ")}
              selected={resource.id === resourceId}
              wide
              onPress={() => { setResourceId(resource.id); resetAttempt(); }}
            />
          )) : !detailsLoading && !detailsError ? <Text style={styles.muted}>{t.booking.noResources}</Text> : null}
        </>
      ) : selectedResource ? (
        <View style={styles.selectedRoomCard}>
          <Text style={styles.selectedRoomActivity}>{t.discovery.activities[activity]}</Text>
          {selectedResource.consoleType ? <View style={styles.selectedRoomBadge}><Text style={styles.selectedRoomBadgeText}>{selectedResource.consoleType}</Text></View> : null}
        </View>
      ) : null}

      {activity === "PLAYSTATION" ? (
        <>
          <Text style={styles.label}>{t.booking.playMode}</Text>
          <View style={styles.chips}>
            {(["SINGLE", "MULTIPLAYER"] as PlayMode[]).map((value) => <Choice key={value} label={t.discovery.playModes[value]} selected={playMode === value} onPress={() => { setPlayMode(value); resetAttempt(); }} />)}
          </View>
        </>
      ) : null}

      <Text style={styles.label}>{t.booking.date}</Text>
      <ScrollView horizontal contentContainerStyle={styles.dateChoices} showsHorizontalScrollIndicator={false}>
        {dateOptions.map((option) => (
          <DateChoice
            accessibilityLabel={option.accessibilityLabel}
            day={option.day}
            key={option.value}
            month={option.month}
            selected={date === option.value}
            weekday={option.weekday}
            onPress={() => { setDate(option.value); setStart(undefined); resetAttempt(); }}
          />
        ))}
      </ScrollView>
      {dateError ? <Text nativeID="booking-date-error" style={styles.fieldError}>{dateError}</Text> : null}

      <Text style={styles.label}>{t.booking.startTime}</Text>
      {phoneVerified === undefined && !profileError ? <View accessibilityRole="progressbar" style={styles.inlineLoading}><ActivityIndicator /><Text>{t.booking.checkingVerification}</Text></View> : null}
      {profileError ? <View accessibilityRole="alert" style={styles.errorBox}><Text style={styles.error}>{profileError}</Text><Pressable onPress={() => void loadProfile()}><Text style={styles.link}>{t.retry}</Text></Pressable></View> : null}
      {phoneVerified === false ? <View style={styles.noticeBox}><Text accessibilityRole="alert" style={styles.noticeText}>{t.booking.phoneVerificationNeeded}</Text><Pressable accessibilityRole="button" onPress={onOpenProfile}><Text style={styles.link}>{t.booking.openProfile}</Text></Pressable></View> : null}
      {phoneVerified && venueSession && !venueSession.closed ? <Text style={styles.sessionHours}>{t.booking.openingWindow(formatTwelveHourTime(venueSession.opensAt), formatTwelveHourTime(venueSession.closesAt))}</Text> : null}
      {phoneVerified && date && venueSession?.closed ? <Text style={styles.hint}>{t.booking.closedOnDate}</Text> : phoneVerified && date ? (
        <View accessibilityLabel={t.booking.startTime} style={styles.timeGrid}>
          {timeOptions.map((option) => (
            <Pressable
              accessibilityRole="button"
              accessibilityState={{ selected: start?.key === option.key }}
              key={option.key}
              onPress={() => { setStart(option); resetAttempt(); }}
              style={[styles.timeChoice, start?.key === option.key && styles.choiceSelected]}
            >
              <Text style={[styles.choiceText, start?.key === option.key && styles.choiceTextSelected]}>{formatTwelveHourTime(option.time)}</Text>
            </Pressable>
          ))}
        </View>
      ) : phoneVerified ? <Text style={styles.hint}>{t.booking.chooseDateFirst}</Text> : null}
      {timeError ? <Text nativeID="booking-time-error" style={styles.fieldError}>{timeError}</Text> : null}

      <Text style={styles.label}>{t.booking.duration}</Text>
      <ScrollView horizontal contentContainerStyle={styles.chips} showsHorizontalScrollIndicator={false}>
        {durationOptions.map((value) => <Choice key={value} label={t.booking.durationValue(value)} selected={durationMinutes === value} onPress={() => { setDurationMinutes(value); resetAttempt(); }} />)}
      </ScrollView>
      <Text style={styles.hint}>{t.booking.durationHint}</Text>

      {quoteError ? <View accessibilityRole="alert" style={styles.errorBox}><Text style={styles.error}>{quoteError}</Text><Pressable accessibilityRole="button" onPress={() => void requestQuote()}><Text style={styles.link}>{t.booking.retryPrice}</Text></Pressable></View> : null}
      <Pressable accessibilityRole="button" accessibilityState={{ disabled: !canQuote || quoteLoading }} disabled={!canQuote || quoteLoading} onPress={() => void requestQuote()} style={[styles.primaryButton, (!canQuote || quoteLoading) && styles.disabledButton]}>
        {quoteLoading ? <ActivityIndicator color={nativeTokens.color.text.inverse} /> : <Text style={styles.primaryButtonText}>{t.booking.reviewPrice}</Text>}
      </Pressable>
    </ScrollView>
  );
}

function PendingBooking({ api, booking: initialBooking, serverTime: initialServerTime, onOpenMyBookings, pollIntervalMs }: { api: ApiClient; booking: Booking; serverTime: string; onOpenMyBookings?(): void; pollIntervalMs: number }) {
  const [booking, setBooking] = useState(initialBooking);
  const [serverTime, setServerTime] = useState(initialServerTime);
  const serverOffset = useRef(0);
  const [remaining, setRemaining] = useState(() => booking.venueResponseExpiresAt ? countdownSeconds(booking.venueResponseExpiresAt, serverTime) : 0);
  useEffect(() => {
    const expiresAt = booking.venueResponseExpiresAt;
    if (booking.venueResponsePaused || !expiresAt) return undefined;
    serverOffset.current = serverClockOffset(serverTime, Date.now());
    const update = () => setRemaining(countdownSecondsAt(expiresAt, Date.now() + serverOffset.current));
    update();
    const interval = setInterval(update, 1000);
    return () => clearInterval(interval);
  }, [booking.venueResponseExpiresAt, booking.venueResponsePaused, serverTime]);
  useEffect(() => {
    if (booking.status !== "PENDING") return undefined;
    let active = true;
    const refresh = async () => {
      try {
        const { data } = await api.GET("/api/v1/customer/bookings/{bookingId}", {
          params: { path: { bookingId: booking.bookingId } },
        });
        if (active && data) {
          setBooking(data.data);
          setServerTime(data.meta.serverTime);
        }
      } catch {
        // The visible countdown remains useful; the next poll or push can recover.
      }
    };
    const interval = setInterval(() => void refresh(), pollIntervalMs);
    return () => { active = false; clearInterval(interval); };
  }, [api, booking.bookingId, booking.status, pollIntervalMs]);
  return (
    <ScrollView contentContainerStyle={styles.pendingPage}>
      <View style={styles.pendingMark}><ActivityIndicator color={nativeTokens.color.feedback.warning} size="large" /></View>
      <Text accessibilityRole="header" style={styles.title}>{booking.status === "PENDING" ? t.booking.pendingTitle : t.booking.statusUpdated}</Text>
      <Text style={styles.muted}>{booking.status === "PENDING" ? t.booking.pendingBody : t.booking.statusUpdatedBody}</Text>
      {booking.status === "PENDING" && (booking.venueResponsePaused
        ? <Text style={styles.timerNotice}>{t.booking.countdownPaused}</Text>
        : remaining > 0
          ? <><Text style={styles.timer}>{formatCountdown(remaining)}</Text><Text style={styles.hint}>{t.booking.countdownLabel}</Text></>
          : <Text style={styles.timerNotice}>{t.booking.countdownExpired}</Text>)}
      <View style={styles.summaryCard}>
        <Text style={styles.venue}>{booking.venue.name}</Text>
        <Text style={styles.muted}>{[booking.resource.name, booking.resource.consoleType].filter(Boolean).join(" · ")}</Text>
        <SummaryRow label={t.booking.total} value={formatMoney(booking.totalAmount) ?? ""} />
        {booking.venue.phone ? <SummaryRow label={t.booking.venuePhone} value={booking.venue.phone} /> : null}
      </View>
      {booking.history.length ? (
        <View style={styles.summaryCard}>
          <Text style={styles.venue}>{t.booking.statusTimeline}</Text>
          {booking.history.map((entry, index) => (
            <Text key={`${entry.at}-${index}`} style={styles.muted}>
              {`${formatPendingDateTime(entry.at)} · ${entry.toStatus ? bookingStatusText(entry.toStatus) : entry.eventType ? bookingEventText(entry.eventType) : t.myBookings.bookingUpdate}`}
            </Text>
          ))}
        </View>
      ) : null}
      <Pressable accessibilityRole="button" onPress={onOpenMyBookings} style={styles.primaryButton}><Text style={styles.primaryButtonText}>{t.booking.viewMyBookings}</Text></Pressable>
    </ScrollView>
  );
}

function SummaryRow({ label, value, emphasis = false }: { label: string; value: string; emphasis?: boolean }) {
  return <View style={styles.summaryRow}><Text style={emphasis ? styles.summaryStrong : styles.muted}>{label}</Text><Text style={emphasis ? styles.total : styles.summaryValue}>{value}</Text></View>;
}

function Choice({ accessibilityLabel, label, selected, wide = false, onPress }: { accessibilityLabel?: string; label: string; selected: boolean; wide?: boolean; onPress(): void }) {
  return <Pressable accessibilityLabel={accessibilityLabel} accessibilityRole="button" accessibilityState={{ selected }} onPress={onPress} style={[styles.choice, wide && styles.choiceWide, selected && styles.choiceSelected]}><Text style={[styles.choiceText, selected && styles.choiceTextSelected]}>{label}</Text></Pressable>;
}

function DateChoice({ accessibilityLabel, day, month, selected, weekday, onPress }: { accessibilityLabel: string; day: string; month: string; selected: boolean; weekday: string; onPress(): void }) {
  return (
    <Pressable accessibilityLabel={accessibilityLabel} accessibilityRole="button" accessibilityState={{ selected }} onPress={onPress} style={[styles.dateChoice, selected && styles.dateChoiceSelected]}>
      <Text style={[styles.dateWeekday, selected && styles.dateTextSelected]}>{weekday}</Text>
      <Text style={[styles.dateDay, selected && styles.dateTextSelected]}>{day}</Text>
      <Text style={[styles.dateMonth, selected && styles.dateTextSelected]}>{month}</Text>
    </Pressable>
  );
}

function createIdempotencyKey(): string {
  if (typeof globalThis.crypto?.randomUUID === "function") return globalThis.crypto.randomUUID();
  const bytes = new Uint8Array(16);
  if (typeof globalThis.crypto?.getRandomValues !== "function") throw new Error("Secure randomness is unavailable");
  globalThis.crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = [...bytes].map((value) => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function venueSessionForDate(date: string, openingHours: Details["openingHours"]) {
  if (!date) return undefined;
  const selectedDate = new Date(`${date}T00:00:00.000Z`);
  if (Number.isNaN(selectedDate.valueOf())) return undefined;
  const weekday = (selectedDate.getUTCDay() + 6) % 7;
  const openingDay = openingHours.find((day) => day.weekday === weekday);
  if (!openingDay) return undefined;
  if (openingDay.closed || !openingDay.opensAtLocal || !openingDay.closesAtLocal) return { closed: true as const };
  const opensAt = openingDay.opensAtLocal.slice(0, 5);
  const closesAt = openingDay.closesAtLocal.slice(0, 5);
  const startMinutes = timeToMinutes(opensAt);
  let endMinutes = timeToMinutes(closesAt);
  if (endMinutes <= startMinutes) endMinutes += 24 * 60;
  const closeDate = addCalendarDays(date, Math.floor(endMinutes / (24 * 60)));
  const endsAt = cairoLocalToInstant(closeDate, minutesToTime(endMinutes));
  const times: StartOption[] = [];
  for (let value = Math.ceil(startMinutes / 15) * 15; value < endMinutes; value += 15) {
    const time = minutesToTime(value);
    const optionDate = addCalendarDays(date, Math.floor(value / (24 * 60)));
    times.push({ date: optionDate, key: `${optionDate}:${time}`, time });
  }
  return { closed: false as const, closesAt, endsAt, opensAt, times };
}

function isOnlineIntakeOpen(currentServerTime: number, openingHours: Details["openingHours"], openNow: boolean): boolean {
  if (!openNow) return false;
  if (openingHours.length === 0) return openNow;
  const parts = new Intl.DateTimeFormat("en-CA", { day: "2-digit", month: "2-digit", timeZone: "Africa/Cairo", year: "numeric" })
    .formatToParts(new Date(currentServerTime))
    .reduce<Record<string, string>>((result, part) => ({ ...result, [part.type]: part.value }), {});
  const cairoDate = `${parts.year}-${parts.month}-${parts.day}`;
  for (const date of [cairoDate, addCalendarDays(cairoDate, -1)]) {
    const selectedDate = new Date(`${date}T00:00:00.000Z`);
    const weekday = (selectedDate.getUTCDay() + 6) % 7;
    const day = openingHours.find((value) => value.weekday === weekday);
    if (!day || day.closed || !day.opensAtLocal || !day.closesAtLocal) continue;
    const opens = day.opensAtLocal.slice(0, 5);
    const closes = day.closesAtLocal.slice(0, 5);
    const overnight = timeToMinutes(closes) <= timeToMinutes(opens);
    const opensAt = cairoLocalToInstant(date, opens);
    const closesAt = cairoLocalToInstant(overnight ? addCalendarDays(date, 1) : date, closes);
    if (opensAt && closesAt && currentServerTime >= Date.parse(opensAt) && currentServerTime < Date.parse(closesAt) - 10 * 60_000) return true;
  }
  return false;
}

function addCalendarDays(date: string, days: number) {
  const value = new Date(`${date}T00:00:00.000Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

function timeToMinutes(value: string) {
  const [hours = "0", minutes = "0"] = value.split(":");
  return Number(hours) * 60 + Number(minutes);
}

function formatBookingDate(value: string) {
  return new Intl.DateTimeFormat("en", { day: "numeric", month: "short", timeZone: "UTC", weekday: "short", year: "numeric" })
    .format(new Date(`${value}T00:00:00.000Z`));
}

function formatPendingDateTime(value: string) {
  return new Intl.DateTimeFormat("en-EG", { day: "numeric", hour: "numeric", hour12: true, minute: "2-digit", month: "short", timeZone: "Africa/Cairo" }).format(new Date(value));
}

function isCalendarDate(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/u.exec(value);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return parsed.getUTCFullYear() === year && parsed.getUTCMonth() === month - 1 && parsed.getUTCDate() === day;
}

function isClockTime(value: string) {
  return /^([01]\d|2[0-3]):[0-5]\d$/u.test(value);
}

const styles = StyleSheet.create({
  page: { backgroundColor: nativeTokens.color.surface.raised, gap: nativeTokens.spacing.xl, paddingBottom: nativeTokens.spacing["10xl"], paddingTop: nativeTokens.spacing["2xl"], ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["5xl"]) },
  pendingPage: { alignItems: "center", backgroundColor: nativeTokens.color.surface.raised, gap: nativeTokens.spacing.xl, paddingBottom: nativeTokens.spacing["8xl"], paddingTop: nativeTokens.spacing["8xl"], ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["5xl"]) },
  header: { alignItems: "center", borderBottomColor: nativeTokens.color.border.subtle, borderBottomWidth: nativeTokens.spacing.hairline, flexDirection: "row", gap: nativeTokens.spacing.xl, marginBottom: nativeTokens.spacing.md, paddingBottom: nativeTokens.spacing["2xl"] },
  headerCopy: { flex: 1 },
  headerTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.title, fontWeight: nativeTokens.typography.fontWeight.bold },
  headerSubtitle: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.caption, marginTop: nativeTokens.spacing.xs },
  backButton: { alignItems: "center", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius.xl, borderWidth: nativeTokens.spacing.hairline, height: 38, justifyContent: "center", width: 38 },
  backIcon: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.heading, lineHeight: 27 },
  link: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  title: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.heading, fontWeight: nativeTokens.typography.fontWeight.extrabold, textAlign: "center" },
  venue: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodyLarge, fontWeight: nativeTokens.typography.fontWeight.bold },
  label: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.label, fontWeight: nativeTokens.typography.fontWeight.semibold, marginTop: nativeTokens.spacing.md },
  chips: { flexDirection: "row", gap: nativeTokens.spacing.md },
  dateChoices: { flexDirection: "row", gap: nativeTokens.spacing.md, paddingBottom: nativeTokens.spacing.xs },
  dateChoice: { alignItems: "center", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 76, minWidth: 68, paddingHorizontal: nativeTokens.spacing.lg, paddingVertical: nativeTokens.spacing.md },
  dateChoiceSelected: { backgroundColor: nativeTokens.color.brand.primary, borderColor: nativeTokens.color.brand.primary },
  dateWeekday: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.caption, fontWeight: nativeTokens.typography.fontWeight.semibold },
  dateDay: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodyLarge, fontWeight: nativeTokens.typography.fontWeight.extrabold, marginVertical: nativeTokens.spacing.xxs },
  dateMonth: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.caption },
  dateTextSelected: { color: nativeTokens.color.text.inverse },
  choice: { alignItems: "center", alignSelf: "flex-start", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 44, paddingHorizontal: nativeTokens.spacing["3xl"] },
  choiceWide: { alignSelf: "stretch", minHeight: 58 },
  choiceSelected: { backgroundColor: nativeTokens.color.surface.info, borderColor: nativeTokens.color.brand.primary },
  choiceText: { color: nativeTokens.color.text.customer, fontWeight: nativeTokens.typography.fontWeight.semibold },
  choiceTextSelected: { color: nativeTokens.color.brand.primary },
  selectedRoomCard: { alignItems: "center", backgroundColor: nativeTokens.color.surface.canvas, borderColor: nativeTokens.color.border.subtle, borderRadius: nativeTokens.radius["4xl"], borderWidth: nativeTokens.spacing.hairline, flexDirection: "row", gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["3xl"] },
  selectedRoomActivity: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.body, fontWeight: nativeTokens.typography.fontWeight.bold },
  selectedRoomBadge: { backgroundColor: nativeTokens.color.text.customer, borderRadius: nativeTokens.radius.md, paddingHorizontal: nativeTokens.spacing.md, paddingVertical: nativeTokens.spacing.xxs },
  selectedRoomBadgeText: { color: nativeTokens.color.text.inverse, fontSize: nativeTokens.typography.fontSize.caption, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  sessionHours: { color: nativeTokens.color.text.subtle, fontSize: nativeTokens.typography.fontSize.caption, textAlign: "right" },
  timeGrid: { flexDirection: "row", flexWrap: "wrap", gap: nativeTokens.spacing.md },
  timeChoice: { alignItems: "center", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"], borderWidth: nativeTokens.spacing.hairline, flexBasis: "22%", flexGrow: 1, justifyContent: "center", minHeight: 44, paddingHorizontal: nativeTokens.spacing.xs },
  fieldError: { color: nativeTokens.color.feedback.danger, fontSize: nativeTokens.typography.fontSize.caption },
  hint: { color: nativeTokens.color.text.subtle, fontSize: nativeTokens.typography.fontSize.caption },
  muted: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.bodySmall },
  inlineLoading: { alignItems: "center", flexDirection: "row", gap: nativeTokens.spacing.md },
  primaryButton: { alignItems: "center", backgroundColor: nativeTokens.color.brand.primary, borderRadius: nativeTokens.radius["3xl"], justifyContent: "center", marginTop: nativeTokens.spacing["3xl"], minHeight: 54, ...nativeTokens.shadow.action },
  disabledButton: { backgroundColor: nativeTokens.color.text.subtle, shadowOpacity: 0 },
  primaryButtonText: { color: nativeTokens.color.text.inverse, fontWeight: nativeTokens.typography.fontWeight.bold },
  errorBox: { backgroundColor: nativeTokens.color.feedback.dangerSurface, borderRadius: nativeTokens.radius["4xl"], gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["3xl"] },
  error: { color: nativeTokens.color.feedback.danger },
  notice: { backgroundColor: nativeTokens.color.surface.info, borderRadius: nativeTokens.radius["2xl"], color: nativeTokens.color.text.muted, padding: nativeTokens.spacing["2xl"] },
  noticeBox: { backgroundColor: nativeTokens.color.surface.info, borderRadius: nativeTokens.radius["2xl"], gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["2xl"] },
  noticeText: { color: nativeTokens.color.text.customer },
  summaryRow: { alignItems: "center", borderBottomColor: nativeTokens.color.border.subtle, borderBottomWidth: nativeTokens.spacing.hairline, flexDirection: "row", justifyContent: "space-between", paddingVertical: nativeTokens.spacing.xl, width: "100%" },
  summaryValue: { color: nativeTokens.color.text.customer, fontWeight: nativeTokens.typography.fontWeight.semibold },
  summaryStrong: { color: nativeTokens.color.text.customer, fontWeight: nativeTokens.typography.fontWeight.bold },
  total: { color: nativeTokens.color.brand.primary, fontSize: nativeTokens.typography.fontSize.bodyLarge, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  pendingMark: { alignItems: "center", backgroundColor: nativeTokens.color.feedback.warningSurface, borderRadius: nativeTokens.radius.round, height: 86, justifyContent: "center", width: 86 },
  timer: { backgroundColor: nativeTokens.color.feedback.warningSurface, borderRadius: nativeTokens.radius["2xl"], color: nativeTokens.color.feedback.warning, fontSize: nativeTokens.typography.fontSize.title, fontWeight: nativeTokens.typography.fontWeight.extrabold, overflow: "hidden", paddingHorizontal: nativeTokens.spacing["4xl"], paddingVertical: nativeTokens.spacing.lg },
  timerNotice: { color: nativeTokens.color.feedback.warning, fontWeight: nativeTokens.typography.fontWeight.bold, textAlign: "center" },
  summaryCard: { backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["5xl"], borderWidth: nativeTokens.spacing.hairline, padding: nativeTokens.spacing["4xl"], width: "100%", ...nativeTokens.shadow.card },
});
