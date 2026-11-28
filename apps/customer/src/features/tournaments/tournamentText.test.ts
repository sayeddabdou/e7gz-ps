import type { components } from "@e7gz/api-client";
import openApi from "../../../../../packages/api-client/openapi.json";
import { en } from "../../i18n/en";
import { tournamentErrorMessage } from "./errorMessages";
import { participantStatusText, tournamentTerminalReasonText } from "./tournamentText";

type TerminalReason = components["schemas"]["TournamentTerminalReason"];

const reasons = openApi.components.schemas.TournamentTerminalReason.enum as TerminalReason[];

describe("tournament copy", () => {
  it.each(reasons)("maps terminal reason %s", (reason) => {
    const text = tournamentTerminalReasonText(reason);
    expect(text).toBeTruthy();
    expect(text).not.toBe(reason);
  });

  it("matches every terminal reason in the generated API contract exactly", () => {
    const copyKeys = Object.keys(en.tournaments.terminalReasons).filter((key) => key !== "fallback");
    expect(copyKeys.sort()).toEqual([...reasons].sort());
  });

  it("has a generic terminal fallback", () => {
    expect(tournamentTerminalReasonText("NEW_SERVER_REASON")).toContain("ended");
  });

  it.each([
    "TOURNAMENT_CAPACITY_REACHED",
    "TOURNAMENT_JOIN_WINDOW_CLOSED",
    "PENDING_TOURNAMENT_REQUEST_EXISTS",
    "TOURNAMENT_RETRY_COOLDOWN_ACTIVE",
    "TOURNAMENT_CREATION_CUTOFF_PASSED",
    "TOURNAMENT_RESOURCE_CONFLICT",
  ])("maps required API error %s", (code) => {
    expect(tournamentErrorMessage(code)).not.toBe(tournamentErrorMessage("UNKNOWN"));
  });

  it("does not expose an ineligible cause", () => {
    expect(participantStatusText("INELIGIBLE")).toBe("Ineligible");
    expect(participantStatusText("INELIGIBLE").toLowerCase()).not.toContain("ban");
  });
});
