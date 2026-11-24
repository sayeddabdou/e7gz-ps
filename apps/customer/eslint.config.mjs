import js from "@eslint/js";
import reactHooks from "eslint-plugin-react-hooks";
import tseslint from "typescript-eslint";
import hardcodedColors from "../../packages/tokens/eslint/no-hardcoded-colors.js";

export default tseslint.config(
  { ignores: ["dist/**", ".expo/**"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  reactHooks.configs.flat.recommended,
  {
    files: ["plugins/**/*.cjs"],
    languageOptions: {
      sourceType: "commonjs",
      globals: { module: "readonly", require: "readonly" },
    },
    rules: { "@typescript-eslint/no-require-imports": "off" },
  },
  {
    plugins: { e7gz: hardcodedColors },
    rules: { "e7gz/no-hardcoded-colors": "error" },
  },
);
