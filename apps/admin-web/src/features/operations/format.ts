export const adminDateTime = (value: string | null) => value
  ? new Intl.DateTimeFormat("en-EG", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value))
  : "—";

export const codeLabel = (value: string | null) => value
  ? value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase())
  : "—";
