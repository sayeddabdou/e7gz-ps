import type { components } from "@e7gz/api-client";
import { en as t } from "../../i18n/en";

export type TournamentStatus = components["schemas"]["TournamentStatus"];
export type TournamentTerminalReason = components["schemas"]["TournamentTerminalReason"];
export type ParticipantStatus = components["schemas"]["TournamentParticipantStatus"];

export function tournamentStatusText(status: TournamentStatus): string {
  return t.tournaments.statuses[status];
}
export function tournamentTerminalReasonText(reason: TournamentTerminalReason | string | null | undefined): string | undefined {
  if (!reason) return undefined;
  return t.tournaments.terminalReasons[reason as TournamentTerminalReason]
    ?? t.tournaments.terminalReasons.fallback;
}

export function participantStatusText(status: ParticipantStatus): string {
  const values: Record<ParticipantStatus, string> = {
    JOINED: t.tournaments.joined,
    LEFT: t.tournaments.left,
    REMOVED: t.tournaments.removed,
    INELIGIBLE: t.tournaments.ineligible,
  };
  return values[status];
}
