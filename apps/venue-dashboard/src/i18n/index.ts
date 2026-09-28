// EN + AR resources for the Dashboard (FR-VEN-40, NFR-33) — only what the current screen needs. The full language
// resolution (account preference → browser storage → English) is E7-19.
import { createContext, useContext } from "react";

export type Language = "en" | "ar";

const en = {
  appTitle: "Venue Dashboard",
  switchLanguage: "العربية",
  configMissing: "Missing configuration:",
  resourcesTitle: "Resources",
  loading: "Loading…",
  loadFailed: "Could not load the resources.",
  errorCode: "Error code",
  networkError: "The server could not be reached.",
  empty: "No resources yet.",
  retry: "Retry",
  activity: { PLAYSTATION: "PlayStation", BILLIARDS: "Billiards", PING_PONG: "Ping Pong" },
  baseState: { ACTIVE: "Active", DISABLED: "Disabled", ARCHIVED: "Archived" },
  restriction: { NONE: "", OWNER_REVIEW_REQUIRED: "Owner review required" },
};

export type Messages = typeof en;

const ar: Messages = {
  appTitle: "لوحة تحكم المكان",
  switchLanguage: "English",
  configMissing: "إعدادات ناقصة:",
  resourcesTitle: "الغرف والطاولات",
  loading: "جارٍ التحميل…",
  loadFailed: "تعذّر تحميل الغرف والطاولات.",
  errorCode: "رمز الخطأ",
  networkError: "تعذّر الوصول إلى الخادم.",
  empty: "لا توجد غرف أو طاولات بعد.",
  retry: "إعادة المحاولة",
  activity: { PLAYSTATION: "بلايستيشن", BILLIARDS: "بلياردو", PING_PONG: "تنس طاولة" },
  baseState: { ACTIVE: "نشطة", DISABLED: "معطّلة", ARCHIVED: "مؤرشفة" },
  restriction: { NONE: "", OWNER_REVIEW_REQUIRED: "تتطلب مراجعة المالك" },
};

export const messages: Record<Language, Messages> = { en, ar };

export function directionOf(language: Language): "ltr" | "rtl" {
  return language === "ar" ? "rtl" : "ltr";
}

export const MessagesContext = createContext<Messages>(en);

export function useMessages(): Messages {
  return useContext(MessagesContext);
}
