// Minimal app shell: configuration check and one screen. Tab navigation from the prototype, token storage and refresh
// are E7-20.
import { useMemo } from "react";
import { SafeAreaView, Text } from "react-native";
import { StatusBar } from "expo-status-bar";
import { createCustomerApiClient } from "../api";
import type { ConfigResult } from "../config";
import { AreaList } from "../features/discovery/AreaList";
import { en as t } from "../i18n/en";

export function App({ config }: { config: ConfigResult }) {
  const api = useMemo(() => (config.ok ? createCustomerApiClient(config.config) : undefined), [config]);

  return (
    <SafeAreaView>
      <Text accessibilityRole="header">{t.appTitle}</Text>
      {api ? (
        <AreaList api={api} />
      ) : (
        <Text accessibilityRole="alert">{`${t.configMissing} ${config.ok ? "" : config.missing.join(", ")}`}</Text>
      )}
      <StatusBar style="auto" />
    </SafeAreaView>
  );
}
