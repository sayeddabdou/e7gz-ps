/**
 * Platform-neutral visual primitives extracted from the three design prototypes.
 * Keep this file free of CSS units and browser-only values so React Native can
 * consume it directly.
 */
export const color = {
  brand: { primary: "#1668E3", venue: "#1656E8" },
  text: {
    customer: "#0F1B2D",
    venue: "#0F1A33",
    admin: "#111A2B",
    muted: "#6B7685",
    venueMuted: "#7C879E",
    adminMuted: "#6B7688",
    subtle: "#8A94A6",
    inverse: "#FFFFFF",
  },
  surface: {
    canvas: "#F7F9FC",
    raised: "#FFFFFF",
    customerSubtle: "#F4F6F9",
    venueSubtle: "#F8FAFD",
    adminSubtle: "#FCFDFE",
    selected: "#EEF3FF",
    info: "#EAF2FE",
  },
  border: {
    customer: "#E7EBF0",
    venue: "#D9DEE9",
    admin: "#E1E7F0",
    subtle: "#EEF1F6",
  },
  feedback: {
    success: "#0F7A3D",
    successSurface: "#E6F7EE",
    warning: "#A65A00",
    warningSurface: "#FDF1E0",
    danger: "#B3261E",
    dangerSurface: "#FCEBEA",
    info: "#1D4F91",
    infoSurface: "#EAF2FE",
  },
} as const;

export const fontFamilyName = {
  sans: "Plus Jakarta Sans",
  rtl: "Cairo",
  mono: "IBM Plex Mono",
} as const;

export const fontSize = {
  caption: 11,
  label: 12,
  bodySmall: 13,
  body: 14,
  bodyLarge: 15,
  titleSmall: 16,
  title: 18,
  headingSmall: 20,
  heading: 24,
  displaySmall: 28,
  display: 32,
} as const;

export const fontWeight = {
  regular: 400,
  medium: 500,
  semibold: 600,
  bold: 700,
  extrabold: 800,
} as const;

export const lineHeight = { tight: 1.2, snug: 1.3, normal: 1.5, relaxed: 1.6 } as const;

export const typography = { fontFamilyName, fontSize, fontWeight, lineHeight } as const;

export const spacing = {
  none: 0,
  hairline: 1,
  xxs: 2,
  xs: 4,
  sm: 6,
  md: 8,
  lg: 10,
  xl: 12,
  "2xl": 14,
  "3xl": 16,
  "4xl": 18,
  "5xl": 20,
  "6xl": 22,
  "7xl": 24,
  "8xl": 32,
  "9xl": 40,
  "10xl": 48,
  "11xl": 56,
  "12xl": 64,
} as const;

export const radius = {
  none: 0,
  xs: 4,
  sm: 6,
  md: 8,
  lg: 10,
  xl: 12,
  "2xl": 14,
  "3xl": 16,
  "4xl": 18,
  "5xl": 20,
  round: 999,
} as const;

export const tokens = { color, typography, spacing, radius } as const;
export type Tokens = typeof tokens;
