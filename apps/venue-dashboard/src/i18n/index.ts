// EN + AR resources for the Dashboard (FR-VEN-40, NFR-33). Components do not own user-facing copy.
import { createContext, useContext } from "react";

export type Language = "en" | "ar";

const en = {
  appTitle: "Venue Dashboard",
  switchLanguage: "العربية",
  navigationLabel: "Dashboard navigation",
  authRequiredTitle: "Sign in required",
  authRequiredDescription: "Sign in to access the venue dashboard.",
  auth: {
    title: "Venue sign in",
    identifier: "Login identifier",
    password: "Password",
    submit: "Sign in",
    working: "Signing in…",
    restoring: "Restoring your session…",
    logout: "Log out",
    loggingOut: "Logging out…",
    errors: {
      unauthenticated: "The login identifier or password is incorrect.",
      rateLimited: "Too many attempts. Try again later.",
      validationFailed: "Check the information and try again.",
      unexpected: "Something went wrong. Try again.",
    },
  },
  configMissing: "Missing configuration:",
  navigation: {
    overview: "Overview",
    pendingRequests: "Pending Requests",
    missedRequests: "Missed / Expired Requests",
    bookings: "Bookings",
    manualBooking: "Manual Booking",
    rooms: "Rooms",
    pricing: "Pricing",
    schedule: "Schedule",
    maintenance: "Maintenance & Closures",
    reviews: "Reviews",
    tournaments: "Tournaments",
    bookingHistory: "Booking History",
    settings: "Settings",
  },
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
  navigationLabel: "التنقل في لوحة التحكم",
  authRequiredTitle: "تسجيل الدخول مطلوب",
  authRequiredDescription: "سجّل الدخول للوصول إلى لوحة تحكم المكان.",
  auth: {
    title: "تسجيل دخول المكان",
    identifier: "معرّف تسجيل الدخول",
    password: "كلمة المرور",
    submit: "تسجيل الدخول",
    working: "جارٍ تسجيل الدخول…",
    restoring: "جارٍ استعادة الجلسة…",
    logout: "تسجيل الخروج",
    loggingOut: "جارٍ تسجيل الخروج…",
    errors: {
      unauthenticated: "معرّف تسجيل الدخول أو كلمة المرور غير صحيحة.",
      rateLimited: "محاولات كثيرة. حاول مرة أخرى لاحقًا.",
      validationFailed: "راجع البيانات وحاول مرة أخرى.",
      unexpected: "حدث خطأ. حاول مرة أخرى.",
    },
  },
  configMissing: "إعدادات ناقصة:",
  navigation: {
    overview: "نظرة عامة",
    pendingRequests: "طلبات الحجز المعلقة",
    missedRequests: "طلبات لم يتم الرد عليها",
    bookings: "الحجوزات",
    manualBooking: "حجز يدوي",
    rooms: "الغرف",
    pricing: "الأسعار",
    schedule: "الجدول",
    maintenance: "الصيانة والإغلاقات",
    reviews: "التقييمات",
    tournaments: "البطولات",
    bookingHistory: "سجل الحجوزات",
    settings: "الإعدادات",
  },
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
