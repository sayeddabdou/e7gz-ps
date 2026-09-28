import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./app/App";
import { readConfig } from "./config";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App config={readConfig(import.meta.env)} />
  </StrictMode>,
);
