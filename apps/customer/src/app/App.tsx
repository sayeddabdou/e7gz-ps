import { type ComponentProps, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { StatusBar } from "expo-status-bar";
import Ionicons from "@expo/vector-icons/Ionicons";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { createNavigationContainerRef, DefaultTheme, NavigationContainer, useFocusEffect } from "@react-navigation/native";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import { nativeTokens } from "@e7gz/tokens/native";
import { createCustomerApiClient, type ApiClient, type RefreshAccessToken } from "../api";
import { createSecureTokenStore, type TokenStore } from "../auth/tokenStore";
import type { ConfigResult } from "../config";
import { DiscoveryScreen } from "../features/discovery";
import { AuthFlow, createCustomerAuthApi } from "../features/auth";
import type { CustomerAuthApi } from "../features/auth/api";
import { MyBookingsScreen } from "../features/my-bookings";
import {
  createExpoPushAdapter,
  createPushRegistration,
  createSecurePushRegistrationStore,
  type PushRegistration,
  type PushSetupState,
} from "../features/notifications/pushRegistration";
import { en as t } from "../i18n/en";

type RootTabs = {
  home: undefined;
  tournaments: undefined;
  bookings: undefined;
  profile: undefined;
};

const Tab = createBottomTabNavigator<RootTabs>();
const navigationRef = createNavigationContainerRef<RootTabs>();
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
  const [focusVersion, setFocusVersion] = useState(0);
  const initialFocus = useRef(true);
  useFocusEffect(useCallback(() => {
    if (initialFocus.current) initialFocus.current = false;
    else setFocusVersion((version) => version + 1);
  }, []));
  return (
    <SafeAreaView edges={["top"]} style={styles.screen}>
      <DiscoveryScreen api={api} focusVersion={focusVersion} />
    </SafeAreaView>
  );
}

function BookingsScreen({
  api,
  openBookingId,
  onOpenBookingConsumed,
}: {
  api: ApiClient;
  openBookingId?: string;
  onOpenBookingConsumed(): void;
}) {
  const [focusVersion, setFocusVersion] = useState(0);
  const initialFocus = useRef(true);
  useFocusEffect(useCallback(() => {
    if (initialFocus.current) initialFocus.current = false;
    else setFocusVersion((version) => version + 1);
  }, []));
  return (
    <SafeAreaView edges={["top"]} style={styles.screen}>
      <MyBookingsScreen
        api={api}
        focusVersion={focusVersion}
        openBookingId={openBookingId}
        onOpenBookingConsumed={onOpenBookingConsumed}
      />
    </SafeAreaView>
  );
}

export function App({
  config,
  tokenStore,
  refreshAccessToken,
  authApi,
  pushRegistrationOverride,
}: {
  config: ConfigResult;
  tokenStore?: TokenStore;
  refreshAccessToken?: RefreshAccessToken;
  authApi?: CustomerAuthApi;
  pushRegistrationOverride?: PushRegistration;
}) {
  const secureTokens = useMemo(() => tokenStore ?? createSecureTokenStore(), [tokenStore]);
  const customerAuth = useMemo(
    () => config.ok ? (authApi ?? createCustomerAuthApi(config.config, secureTokens)) : undefined,
    [authApi, config, secureTokens],
  );
  const [authenticated, setAuthenticated] = useState<boolean>();
  const [pushBookingId, setPushBookingId] = useState<string>();
  const [pushSetupState, setPushSetupState] = useState<PushSetupState>();
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
  const pushRegistration = useMemo(
    () => pushRegistrationOverride ?? (api ? createPushRegistration(api, createExpoPushAdapter(), createSecurePushRegistrationStore()) : undefined),
    [api, pushRegistrationOverride],
  );
  const openBookingFromNotification = useCallback((bookingId: string) => {
    setPushBookingId(bookingId);
    if (navigationRef.isReady()) navigationRef.navigate("bookings");
  }, []);
  useEffect(() => {
    if (!authenticated || !pushRegistration) return undefined;
    void pushRegistration.start(openBookingFromNotification, setPushSetupState).then(setPushSetupState);
    return () => pushRegistration.stop();
  }, [authenticated, openBookingFromNotification, pushRegistration]);

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
    try {
      await pushRegistration?.revoke();
    } catch {
      // Logging out locally must not depend on reaching the device-token endpoint.
    }
    await customerAuth?.logout();
    setPushSetupState(undefined);
    setAuthenticated(false);
  };

  const pushMessage = pushSetupState === "denied"
    ? t.notifications.pushDenied
    : pushSetupState === "unsupported"
      ? t.notifications.pushUnavailable
      : pushSetupState === "failed"
        ? t.notifications.pushFailed
        : undefined;

  return (
    <SafeAreaProvider>
      <View style={styles.root}>
        {pushMessage ? (
          <SafeAreaView edges={["top"]} style={styles.pushNoticeSafeArea} testID="push-setup-notice">
            <Text accessibilityRole="alert" style={styles.pushNotice}>{pushMessage}</Text>
          </SafeAreaView>
        ) : null}
        <NavigationContainer
          ref={navigationRef}
          theme={navigationTheme}
          onReady={() => { if (pushBookingId) navigationRef.navigate("bookings"); }}
        >
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
              options={{
                tabBarIcon: bookingsIcon,
                tabBarLabel: t.tabs.bookings,
                title: t.tabs.bookings,
              }}
            >
              {() => (
                <BookingsScreen
                  api={api}
                  openBookingId={pushBookingId}
                  onOpenBookingConsumed={() => setPushBookingId(undefined)}
                />
              )}
            </Tab.Screen>
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
  pushNotice: {
    color: nativeTokens.color.text.customer,
    paddingHorizontal: nativeTokens.spacing["4xl"],
    paddingVertical: nativeTokens.spacing.md,
  },
  pushNoticeSafeArea: {
    backgroundColor: nativeTokens.color.feedback.warningSurface,
  },
});
