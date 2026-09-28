import { color, fontFamilyName, radius, spacing, typography } from "./tokens.ts";

export const webFontFamily = {
  sans: `'${fontFamilyName.sans}', system-ui, sans-serif`,
  rtl: `'${fontFamilyName.rtl}', '${fontFamilyName.sans}', Helvetica, Arial, sans-serif`,
  mono: `'${fontFamilyName.mono}', ui-monospace, monospace`,
} as const;

export const webShadow = {
  card: "0 1px 2px rgba(15, 27, 45, 0.04)",
  cardHover: "0 8px 20px rgba(15, 27, 45, 0.08)",
  raised: "0 4px 14px rgba(15, 27, 45, 0.12)",
  action: "0 8px 18px rgba(22, 104, 227, 0.28)",
  dialog: "0 18px 44px rgba(17, 26, 43, 0.14)",
  venueDialog: "0 26px 60px rgba(15, 26, 51, 0.26)",
  focus: "0 0 0 3px rgba(22, 104, 227, 0.10)",
} as const;

export type LogicalSpace = string | number;

/** CSS logical properties keep layout correct in both LTR and RTL. */
export const logicalSpacing = {
  marginInline: (value: LogicalSpace) => ({ marginInline: value }),
  marginInlineStart: (value: LogicalSpace) => ({ marginInlineStart: value }),
  marginInlineEnd: (value: LogicalSpace) => ({ marginInlineEnd: value }),
  paddingInline: (value: LogicalSpace) => ({ paddingInline: value }),
  paddingInlineStart: (value: LogicalSpace) => ({ paddingInlineStart: value }),
  paddingInlineEnd: (value: LogicalSpace) => ({ paddingInlineEnd: value }),
} as const;

export const webTokens = {
  color,
  typography: { ...typography, fontFamily: webFontFamily },
  spacing,
  radius,
  shadow: webShadow,
} as const;

export { color, radius, spacing, typography };
