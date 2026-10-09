import { useState } from "react";
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import type { components } from "@e7gz/api-client";
import { nativeLogicalSpacing, nativeTokens } from "@e7gz/tokens/native";
import { errorCodeOf, errorFieldOf, type ApiClient } from "../../api";
import { en as t } from "../../i18n/en";
import { allHalfHourTimes, buildDateOptions, durationOptions, formatTwelveHourTime, type DateOption } from "../booking/selectionOptions";
import { discoveryErrorMessage } from "./errorMessages";

type Activity = components["schemas"]["Activity"];
type Area = components["schemas"]["CustomerArea"];
type Result = components["schemas"]["CustomerAvailableVenue"];
const activities: Activity[] = ["PLAYSTATION", "BILLIARDS", "PING_PONG"];

export function FindAvailableScreen({ api, areas, initialActivity, referenceDate, onBack, onViewVenue }: {
  api: ApiClient;
  areas: Area[];
  initialActivity: Activity;
  referenceDate: Date;
  onBack(): void;
  onViewVenue(venueId: string, activity: Activity): void;
}) {
  const [areaId, setAreaId] = useState(areas[0]?.id ?? "");
  const [activity, setActivity] = useState(initialActivity);
  const [date, setDate] = useState("");
  const [startTime, setStartTime] = useState("");
  const [dateOptions] = useState(() => buildDateOptions(referenceDate));
  const [durationMinutes, setDurationMinutes] = useState(60);
  const [results, setResults] = useState<Result[]>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();

  const search = async () => {
    setError(undefined);
    if (!areaId || !/^\d{4}-\d{2}-\d{2}$/u.test(date) || !/^([01]\d|2[0-3]):[0-5]\d$/u.test(startTime)) {
      setError(t.findAvailable.errors.incomplete);
      return;
    }
    setLoading(true);
    try {
      const { data, error: body } = await api.GET("/api/v1/customer/find-available", {
        params: { query: { areaId, activity, date, startTime: `${startTime}:00`, durationMinutes } },
      });
      if (data) setResults(data.data);
      else setError(discoveryErrorMessage(errorCodeOf(body), errorFieldOf(body)));
    } catch {
      setError(t.discovery.errors.network);
    } finally {
      setLoading(false);
    }
  };

  return <ScrollView contentContainerStyle={styles.page}>
    <View style={styles.header}><Pressable accessibilityRole="button" accessibilityLabel={t.discovery.back} onPress={onBack} style={styles.back}><Text style={styles.backText}>‹</Text></Pressable><Text accessibilityRole="header" style={styles.title}>{t.findAvailable.title}</Text></View>
    <Text style={styles.intro}>{t.findAvailable.description}</Text>
    <Text style={styles.label}>{t.discovery.area}</Text>
    <ScrollView horizontal contentContainerStyle={styles.row} showsHorizontalScrollIndicator={false}>{areas.map((area) => <Choice key={area.id} label={area.name} selected={area.id === areaId} onPress={() => setAreaId(area.id)} />)}</ScrollView>
    <Text style={styles.label}>{t.discovery.activity}</Text>
    <ScrollView horizontal contentContainerStyle={styles.row} showsHorizontalScrollIndicator={false}>{activities.map((value) => <Choice key={value} label={t.discovery.activities[value]} selected={value === activity} onPress={() => setActivity(value)} />)}</ScrollView>
    <Text style={styles.label}>{t.findAvailable.date}</Text>
    <ScrollView horizontal contentContainerStyle={styles.row} showsHorizontalScrollIndicator={false}>{dateOptions.map((option) => <DateChoice key={option.value} option={option} selected={option.value === date} onPress={() => { setDate(option.value); setStartTime(""); setError(undefined); }} />)}</ScrollView>
    <Text style={styles.label}>{t.findAvailable.startTime}</Text>
    {date ? <View accessibilityLabel={t.findAvailable.startTime} style={styles.timeGrid}>{allHalfHourTimes.map((value) => <Choice key={value} label={formatTwelveHourTime(value)} selected={value === startTime} time onPress={() => { setStartTime(value); setError(undefined); }} />)}</View> : <Text style={styles.hint}>{t.booking.chooseDateFirst}</Text>}
    <Text style={styles.label}>{t.booking.duration}</Text>
    <ScrollView horizontal contentContainerStyle={styles.row} showsHorizontalScrollIndicator={false}>{durationOptions.map((value) => <Choice key={value} label={t.booking.durationValue(value)} selected={value === durationMinutes} onPress={() => setDurationMinutes(value)} />)}</ScrollView>
    {error ? <View accessibilityRole="alert" style={styles.error}><Text style={styles.errorText}>{error}</Text></View> : null}
    <Pressable accessibilityRole="button" disabled={loading} onPress={() => void search()} style={styles.primary}>{loading ? <ActivityIndicator color={nativeTokens.color.text.inverse} /> : <Text style={styles.primaryText}>{t.findAvailable.search}</Text>}</Pressable>
    {results?.length === 0 ? <Text style={styles.empty}>{t.findAvailable.empty}</Text> : null}
    {results?.map((result) => <Pressable accessibilityRole="button" key={result.venueId} onPress={() => onViewVenue(result.venueId, activity)} style={styles.card}><Text style={styles.cardTitle}>{result.venueName}</Text><Text style={styles.muted}>{t.findAvailable.resources(result.resources.length)}</Text>{result.resources.map((resource) => <Text key={resource.id} style={styles.resource}>{[resource.name, resource.consoleType].filter(Boolean).join(" · ")}</Text>)}</Pressable>)}
  </ScrollView>;
}

function Choice({ label, selected, time = false, onPress }: { label: string; selected: boolean; time?: boolean; onPress(): void }) {
  return <Pressable accessibilityRole="button" accessibilityState={{ selected }} onPress={onPress} style={[styles.choice, time && styles.timeChoice, selected && styles.choiceSelected]}><Text style={[styles.choiceText, selected && styles.choiceTextSelected]}>{label}</Text></Pressable>;
}

function DateChoice({ option, selected, onPress }: { option: DateOption; selected: boolean; onPress(): void }) {
  return <Pressable accessibilityLabel={option.accessibilityLabel} accessibilityRole="button" accessibilityState={{ selected }} onPress={onPress} style={[styles.dateChoice, selected && styles.dateChoiceSelected]}><Text style={[styles.dateWeekday, selected && styles.dateTextSelected]}>{option.weekday}</Text><Text style={[styles.dateDay, selected && styles.dateTextSelected]}>{option.day}</Text><Text style={[styles.dateMonth, selected && styles.dateTextSelected]}>{option.month}</Text></Pressable>;
}

const styles = StyleSheet.create({
  page: { backgroundColor: nativeTokens.color.surface.raised, gap: nativeTokens.spacing.xl, paddingBottom: nativeTokens.spacing["10xl"], ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["5xl"]) },
  header: { alignItems: "center", borderBottomColor: nativeTokens.color.border.subtle, borderBottomWidth: nativeTokens.spacing.hairline, flexDirection: "row", gap: nativeTokens.spacing.xl, paddingBottom: nativeTokens.spacing["2xl"], paddingTop: nativeTokens.spacing["2xl"] },
  back: { alignItems: "center", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius.xl, borderWidth: nativeTokens.spacing.hairline, height: 38, justifyContent: "center", width: 38 },
  backText: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.heading },
  title: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.title, fontWeight: nativeTokens.typography.fontWeight.bold },
  intro: { color: nativeTokens.color.text.muted },
  label: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.label, fontWeight: nativeTokens.typography.fontWeight.semibold },
  row: { gap: nativeTokens.spacing.md },
  choice: { borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, minHeight: 44, justifyContent: "center", paddingHorizontal: nativeTokens.spacing["3xl"] },
  choiceSelected: { backgroundColor: nativeTokens.color.surface.info, borderColor: nativeTokens.color.brand.primary },
  choiceText: { color: nativeTokens.color.text.customer, fontWeight: nativeTokens.typography.fontWeight.semibold },
  choiceTextSelected: { color: nativeTokens.color.brand.primary },
  dateChoice: { alignItems: "center", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 76, minWidth: 68, paddingHorizontal: nativeTokens.spacing.lg, paddingVertical: nativeTokens.spacing.md },
  dateChoiceSelected: { backgroundColor: nativeTokens.color.brand.primary, borderColor: nativeTokens.color.brand.primary },
  dateWeekday: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.caption, fontWeight: nativeTokens.typography.fontWeight.semibold },
  dateDay: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodyLarge, fontWeight: nativeTokens.typography.fontWeight.extrabold, marginVertical: nativeTokens.spacing.xxs },
  dateMonth: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.caption },
  dateTextSelected: { color: nativeTokens.color.text.inverse },
  timeGrid: { flexDirection: "row", flexWrap: "wrap", gap: nativeTokens.spacing.md },
  timeChoice: { flexBasis: "22%", flexGrow: 1, paddingHorizontal: nativeTokens.spacing.xs },
  hint: { color: nativeTokens.color.text.subtle, fontSize: nativeTokens.typography.fontSize.caption },
  primary: { alignItems: "center", backgroundColor: nativeTokens.color.brand.primary, borderRadius: nativeTokens.radius["3xl"], justifyContent: "center", minHeight: 54 },
  primaryText: { color: nativeTokens.color.text.inverse, fontWeight: nativeTokens.typography.fontWeight.bold },
  error: { backgroundColor: nativeTokens.color.feedback.dangerSurface, borderRadius: nativeTokens.radius["3xl"], padding: nativeTokens.spacing.xl },
  errorText: { color: nativeTokens.color.feedback.danger },
  empty: { color: nativeTokens.color.text.muted, paddingVertical: nativeTokens.spacing["5xl"], textAlign: "center" },
  card: { borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["4xl"], borderWidth: nativeTokens.spacing.hairline, gap: nativeTokens.spacing.sm, padding: nativeTokens.spacing["3xl"], ...nativeTokens.shadow.card },
  cardTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodyLarge, fontWeight: nativeTokens.typography.fontWeight.bold },
  muted: { color: nativeTokens.color.text.muted },
  resource: { color: nativeTokens.color.text.customer, fontWeight: nativeTokens.typography.fontWeight.semibold },
});
