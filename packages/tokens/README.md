# @e7gz/tokens

Design tokens (colours, typography, spacing, radii and shadows) shared by the three apps, taken from the visual values in `docs/03-design`. Apps never hard-code colours.

## Imports

- `@e7gz/tokens` — platform-neutral primitives.
- `@e7gz/tokens/native` — React Native tokens, native shadows and start/end spacing helpers. It contains no CSS-only values.
- `@e7gz/tokens/web` — web tokens, CSS shadows and logical-property spacing helpers.
- `@e7gz/tokens/css` — the same values as CSS custom properties plus logical-property utility classes.

The package's tests spot-check prototype values, keep the native export CSS-free and reject physical left/right margin helpers.
