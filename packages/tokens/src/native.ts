import { color, radius, spacing, typography } from "./tokens.ts";

/** React Native shadow objects contain no CSS strings or browser-only values. */
export const nativeShadow = {
  card: {
    shadowColor: color.text.customer,
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.04,
    shadowRadius: 2,
    elevation: 1,
  },
  raised: {
    shadowColor: color.text.customer,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.12,
    shadowRadius: 7,
    elevation: 4,
  },
  action: {
    shadowColor: color.brand.primary,
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.28,
    shadowRadius: 9,
    elevation: 6,
  },
} as const;

/** React Native's start/end properties follow the current writing direction. */
export const nativeLogicalSpacing = {
  marginInline: (value: number) => ({ marginStart: value, marginEnd: value }),
  marginInlineStart: (value: number) => ({ marginStart: value }),
  marginInlineEnd: (value: number) => ({ marginEnd: value }),
  paddingInline: (value: number) => ({ paddingStart: value, paddingEnd: value }),
  paddingInlineStart: (value: number) => ({ paddingStart: value }),
  paddingInlineEnd: (value: number) => ({ paddingEnd: value }),
} as const;

export const nativeTokens = {
  color,
  typography,
  spacing,
  radius,
  shadow: nativeShadow,
} as const;

export { color, radius, spacing, typography };
