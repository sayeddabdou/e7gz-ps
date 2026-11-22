import { createElement, type ReactNode } from "react";

/** Isolates stored free text from the surrounding UI direction without changing its bytes. */
export function StoredText({ children, direction = "auto" }: { children: ReactNode; direction?: "auto" | "ltr" }) {
  return createElement("bdi", { dir: direction }, children);
}
