// Minimal app shell: configuration check and one screen. Tab navigation from the prototype, token storage and refresh
// are E7-20.
import { useMemo } from "react";
import { SafeAreaView, StyleSheet, Text } from "react-native";
import { StatusBar } from "expo-status-bar";
import { nativeLogicalSpacing, nativeTokens } from "@e7gz/tokens/native";
import { createCustomerApiClient } from "../api";
import type { ConfigResult } from "../config";
import { AreaList } from "../features/discovery/AreaList";
import { en as t } from "../i18n/en";

export function App({ config }: { config: ConfigResult }) {
  const api = useMemo(() => (config.ok ? createCustomerApiClient(config.config) : undefined), [config]);

  return (
    <SafeAreaView style={styles.root}>
      <Text accessibilityRole="header" style={styles.title}>{t.appTitle}</Text>
      {api ? (
        <AreaList api={api} />
      ) : (
        <Text accessibilityRole="alert">{`${t.configMissing} ${config.ok ? "" : config.missing.join(", ")}`}</Text>
      )}
      <StatusBar style="auto" />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: nativeTokens.color.surface.raised,
    ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["5xl"]),
  },
  title: {
    color: nativeTokens.color.text.customer,
    fontSize: nativeTokens.typography.fontSize.heading,
  },
});
