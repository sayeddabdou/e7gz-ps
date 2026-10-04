import { describe, expect, it } from "vitest";
import { adminErrorMessage } from "./adminErrors";

describe("adminErrorMessage", () => {
  it.each([
    ["AREA_NAME_TAKEN", "An area with this name already exists."],
    ["VENUE_ACTIVITY_IN_USE", "This activity still has rooms or prices and cannot be removed."],
    ["VENUE_LOGIN_IDENTIFIER_TAKEN", "This login identifier is already in use."],
    ["VENUE_ACCOUNT_ALREADY_EXISTS", "This venue already has an account."],
    ["MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED", "This marketplace status change is not allowed."],
    ["VENUE_IMAGE_NOT_ACCEPTED", "This image was not accepted."],
    ["VENUE_IMAGE_LIMIT_REACHED", "The venue photo limit has been reached."],
    ["VENUE_IMAGE_NOT_UPLOADED", "The image upload was not completed."],
  ])("maps %s without exposing the raw code", (code, expected) => {
    expect(adminErrorMessage(code)).toBe(expected);
    expect(adminErrorMessage(code)).not.toContain(code);
  });

  it.each([
    ["areaId", "Choose an existing active area."],
    ["phone", "Enter a valid phone number."],
    ["initialOwnerPassword", "Enter exactly 4 digits."],
    ["password", "Use at least 8 characters with a letter and a number."],
    ["newPassword", "Enter a valid new password."],
  ])("maps VALIDATION_FAILED field %s", (field, expected) => {
    expect(adminErrorMessage("VALIDATION_FAILED", field)).toBe(expected);
  });
});
