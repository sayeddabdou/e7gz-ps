import { useMemo } from "react";
import { StyleSheet, Text, View } from "react-native";
import { StatusBar } from "expo-status-bar";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { DefaultTheme, NavigationContainer } from "@react-navigation/native";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import { nativeLogicalSpacing, nativeTokens } from "@e7gz/tokens/native";
import { createCustomerApiClient, type ApiClient, type RefreshAccessToken } from "../api";
import { createSecureTokenStore, type TokenStore } from "../auth/tokenStore";
import type { ConfigResult } from "../config";
import { AreaList } from "../features/discovery/AreaList";
import { en as t } from "../i18n/en";

type RootTabs = {
  home: undefined;
  tournaments: undefined;
  bookings: undefined;
  profile: undefined;
};

const Tab = createBottomTabNavigator<RootTabs>();

const navigationTheme = {
  ...DefaultTheme,
  colors: {
    ...DefaultTheme.colors,
    primary: nativeTokens.color.brand.primary,
    background: nativeTokens.color.surface.canvas,
    card: nativeTokens.color.surface.raised,
    text: nativeTokens.color.text.customer,
    border: nativeTokens.color.border.customer,
    notification: nativeTokens.color.feedback.danger,
  },
};

function Screen({ title }: { title: string }) {
  return (
    <SafeAreaView edges={["top"]} style={styles.screen}>
      <Text accessibilityRole="header" style={styles.title}>{title}</Text>
    </SafeAreaView>
  );
}

function TournamentsScreen() {
  return <Screen title={t.tabs.tournaments} />;
}

function BookingsScreen() {
  return <Screen title={t.tabs.bookings} />;
}

function ProfileScreen() {
  return <Screen title={t.tabs.profile} />;
}

function HomeScreen({ api }: { api: ApiClient }) {
  return (
    <SafeAreaView edges={["top"]} style={styles.screen}>
      <Text accessibilityRole="header" style={styles.title}>{t.appTitle}</Text>
      <AreaList api={api} />
    </SafeAreaView>
  );
}

export function App({
  config,
  tokenStore,
  refreshAccessToken,
}: {
  config: ConfigResult;
  tokenStore?: TokenStore;
  refreshAccessToken?: RefreshAccessToken;
}) {
  const secureTokens = useMemo(() => tokenStore ?? createSecureTokenStore(), [tokenStore]);
  const api = useMemo(
    () => config.ok
      ? createCustomerApiClient(config.config, {
          tokenStore: secureTokens,
          ...(refreshAccessToken ? { refresh: refreshAccessToken } : {}),
        })
      : undefined,
    [config, refreshAccessToken, secureTokens],
  );

  if (!config.ok || !api) {
    return (
      <SafeAreaProvider>
        <SafeAreaView style={styles.screen}>
          <Text accessibilityRole="alert">{`${t.configMissing} ${config.ok ? "" : config.missing.join(", ")}`}</Text>
          <StatusBar style="auto" />
        </SafeAreaView>
      </SafeAreaProvider>
    );
  }

  return (
    <SafeAreaProvider>
      <View style={styles.root}>
        <NavigationContainer theme={navigationTheme}>
          <Tab.Navigator
            screenOptions={{
              headerShown: false,
              tabBarActiveTintColor: nativeTokens.color.brand.primary,
              tabBarInactiveTintColor: nativeTokens.color.text.subtle,
              tabBarStyle: styles.tabBar,
              tabBarLabelStyle: styles.tabLabel,
            }}
          >
            <Tab.Screen
              name="home"
              options={{ tabBarLabel: t.tabs.home, title: t.tabs.home }}
            >
              {() => <HomeScreen api={api} />}
            </Tab.Screen>
            <Tab.Screen
              name="tournaments"
              component={TournamentsScreen}
              options={{ tabBarLabel: t.tabs.tournaments, title: t.tabs.tournaments }}
            />
            <Tab.Screen
              name="bookings"
              component={BookingsScreen}
              options={{ tabBarLabel: t.tabs.bookings, title: t.tabs.bookings }}
            />
            <Tab.Screen
              name="profile"
              component={ProfileScreen}
              options={{ tabBarLabel: t.tabs.profile, title: t.tabs.profile }}
            />
          </Tab.Navigator>
        </NavigationContainer>
        <StatusBar style="auto" />
      </View>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: nativeTokens.color.surface.canvas,
  },
  screen: {
    flex: 1,
    backgroundColor: nativeTokens.color.surface.raised,
    ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["5xl"]),
    paddingTop: nativeTokens.spacing["3xl"],
  },
  title: {
    color: nativeTokens.color.text.customer,
    fontSize: nativeTokens.typography.fontSize.heading,
    fontWeight: nativeTokens.typography.fontWeight.extrabold,
    marginBottom: nativeTokens.spacing["3xl"],
  },
  tabBar: {
    backgroundColor: nativeTokens.color.surface.raised,
    borderTopColor: nativeTokens.color.border.subtle,
  },
  tabLabel: {
    fontSize: nativeTokens.typography.fontSize.caption,
    fontWeight: nativeTokens.typography.fontWeight.bold,
  },
});
