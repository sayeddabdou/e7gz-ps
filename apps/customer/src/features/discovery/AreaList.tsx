// Active areas from GET /api/v1/customer/areas (arch 09 §3.2) — public, no token — with loading, success and error
// states. A starting point for the area filter of discovery (E7-38).
import { useEffect, useState } from "react";
import { ActivityIndicator, Pressable, Text, View } from "react-native";
import type { components } from "@e7gz/api-client";
import { errorCodeOf, type ApiClient } from "../../api";
import { en as t } from "../../i18n/en";

type CustomerArea = components["schemas"]["CustomerArea"];

type State =
  | { status: "loading" }
  | { status: "success"; areas: CustomerArea[] }
  | { status: "error"; code: string | undefined; network: boolean };

export function AreaList({ api }: { api: ApiClient }) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    api
      .GET("/api/v1/customer/areas", { signal: controller.signal })
      .then(({ data, error }) => {
        setState(data ? { status: "success", areas: data.data } : { status: "error", code: errorCodeOf(error), network: false });
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setState({ status: "error", code: undefined, network: true });
        }
      });
    return () => controller.abort();
  }, [api, attempt]);

  const retry = () => {
    setState({ status: "loading" });
    setAttempt((n) => n + 1);
  };

  return (
    <View>
      <Text accessibilityRole="header">{t.areasTitle}</Text>
      {state.status === "loading" && (
        <View accessibilityRole="progressbar" accessibilityLabel={t.loading}>
          <ActivityIndicator />
          <Text>{t.loading}</Text>
        </View>
      )}
      {state.status === "error" && (
        <View accessibilityRole="alert">
          <Text>{state.network ? t.networkError : t.loadFailed}</Text>
          {state.code ? <Text>{`${t.errorCode}: ${state.code}`}</Text> : null}
          <Pressable accessibilityRole="button" onPress={retry}>
            <Text>{t.retry}</Text>
          </Pressable>
        </View>
      )}
      {state.status === "success" && state.areas.length === 0 && <Text>{t.empty}</Text>}
      {state.status === "success" &&
        state.areas.map((area) => (
          <Text key={area.id} testID="area">
            {area.name}
          </Text>
        ))}
    </View>
  );
}
