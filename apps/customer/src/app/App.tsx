import { type ComponentProps, useEffect, useMemo, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { StatusBar } from "expo-status-bar";
import Ionicons from "@expo/vector-icons/Ionicons";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { DefaultTheme, NavigationContainer } from "@react-navigation/native";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import { nativeTokens } from "@e7gz/tokens/native";
import { createCustomerApiClient, type ApiClient, type RefreshAccessToken } from "../api";
import { createSecureTokenStore, type TokenStore } from "../auth/tokenStore";
import type { ConfigResult } from "../config";
import { DiscoveryScreen } from "../features/discovery";
import { AuthFlow, createCustomerAuthApi } from "../features/auth";
import type { CustomerAuthApi } from "../features/auth/api";
import { en as t } from "../i18n/en";

type RootTabs = {
  home: undefined;
  tournaments: undefined;
  bookings: undefined;
  profile: undefined;
};

const Tab = createBottomTabNavigator<RootTabs>();
type TabIconName = ComponentProps<typeof Ionicons>["name"];

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

function ProfileScreen({ onLogout }: { onLogout(): void }) {
  return (
    <SafeAreaView edges={["top"]} style={styles.screen}>
      <Text accessibilityRole="header" style={styles.title}>{t.tabs.profile}</Text>
      <Pressable accessibilityRole="button" onPress={onLogout} style={styles.logoutButton}>
        <Text style={styles.logoutText}>{t.auth.logout}</Text>
      </Pressable>
    </SafeAreaView>
  );
}

function tabIcon(
  testID: string,
  active: TabIconName,
  inactive: TabIconName,
) {
  return ({ color, focused, size }: { color: string; focused: boolean; size: number }) => (
    <Ionicons
      testID={testID}
      name={focused ? active : inactive}
      color={color}
      size={size}
    />
  );
}

const homeIcon = tabIcon("tab-icon-home", "home", "home-outline");
const tournamentsIcon = tabIcon("tab-icon-tournaments", "trophy", "trophy-outline");
const bookingsIcon = tabIcon("tab-icon-bookings", "calendar", "calendar-outline");
const profileIcon = tabIcon("tab-icon-profile", "person", "person-outline");

function HomeScreen({ api }: { api: ApiClient }) {
  return (
    <SafeAreaView edges={["top"]} style={styles.screen}>
      <DiscoveryScreen api={api} />
    </SafeAreaView>
  );
}

export function App({
  config,
  tokenStore,
  refreshAccessToken,
  authApi,
}: {
  config: ConfigResult;
  tokenStore?: TokenStore;
  refreshAccessToken?: RefreshAccessToken;
  authApi?: CustomerAuthApi;
}) {
  const secureTokens = useMemo(() => tokenStore ?? createSecureTokenStore(), [tokenStore]);
  const customerAuth = useMemo(
    () => config.ok ? (authApi ?? createCustomerAuthApi(config.config, secureTokens)) : undefined,
    [authApi, config, secureTokens],
  );
  const [authenticated, setAuthenticated] = useState<boolean>();
  useEffect(() => {
    if (!config.ok) return undefined;
    let active = true;
    void secureTokens.read().then((tokens) => { if (active) setAuthenticated(Boolean(tokens)); });
    return () => { active = false; };
  }, [config, secureTokens]);
  const api = useMemo(
    () => config.ok
      ? createCustomerApiClient(config.config, {
          tokenStore: secureTokens,
          ...(refreshAccessToken ? { refresh: refreshAccessToken } : customerAuth ? { refresh: customerAuth.refresh } : {}),
        })
      : undefined,
    [config, customerAuth, refreshAccessToken, secureTokens],
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

  if (authenticated === undefined) {
    return <SafeAreaProvider><SafeAreaView style={styles.screen}><Text>{t.loading}</Text></SafeAreaView></SafeAreaProvider>;
  }

  if (!authenticated && customerAuth) {
    return (
      <SafeAreaProvider>
        <AuthFlow api={customerAuth} tokenStore={secureTokens} onAuthenticated={() => setAuthenticated(true)} />
        <StatusBar style="auto" />
      </SafeAreaProvider>
    );
  }

  const logout = async () => {
    await customerAuth?.logout();
    setAuthenticated(false);
  };

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
              options={{
                tabBarIcon: homeIcon,
                tabBarLabel: t.tabs.home,
                title: t.tabs.home,
              }}
            >
              {() => <HomeScreen api={api} />}
            </Tab.Screen>
            <Tab.Screen
              name="tournaments"
              component={TournamentsScreen}
              options={{
                tabBarIcon: tournamentsIcon,
                tabBarLabel: t.tabs.tournaments,
                title: t.tabs.tournaments,
              }}
            />
            <Tab.Screen
              name="bookings"
              component={BookingsScreen}
              options={{
                tabBarIcon: bookingsIcon,
                tabBarLabel: t.tabs.bookings,
                title: t.tabs.bookings,
              }}
            />
            <Tab.Screen
              name="profile"
              options={{
                tabBarIcon: profileIcon,
                tabBarLabel: t.tabs.profile,
                title: t.tabs.profile,
              }}
            >
              {() => <ProfileScreen onLogout={logout} />}
            </Tab.Screen>
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
    borderTopWidth: nativeTokens.spacing.hairline,
    height: 74,
    paddingBottom: nativeTokens.spacing.md,
    paddingTop: nativeTokens.spacing.md,
  },
  tabLabel: {
    fontSize: nativeTokens.typography.fontSize.caption,
    fontWeight: nativeTokens.typography.fontWeight.bold,
    marginTop: nativeTokens.spacing.xs,
  },
  logoutButton: {
    alignItems: "center",
    borderColor: nativeTokens.color.feedback.danger,
    borderRadius: nativeTokens.radius["2xl"],
    borderWidth: nativeTokens.spacing.hairline,
    minHeight: 48,
    justifyContent: "center",
  },
  logoutText: {
    color: nativeTokens.color.feedback.danger,
    fontWeight: nativeTokens.typography.fontWeight.bold,
  },
});
