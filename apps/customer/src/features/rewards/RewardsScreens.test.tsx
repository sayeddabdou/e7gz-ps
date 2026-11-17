import { act, fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import { AppState, Share } from "react-native";
import type { ApiClient } from "../../api";
import {
  AccountStandingScreen,
  PointsScreen,
  ReferralsScreen,
  WalletScreen,
  remainingBanText,
} from "./RewardsScreens";

const meta = { correlationId: "c-1", serverTime: "2026-11-17T10:00:00Z" };

function apiFor(path: string, data: unknown): ApiClient {
  return {
    GET: jest.fn(async (requestedPath: string) => requestedPath === path
      ? { data: { data, meta } }
      : { error: { error: { code: "NOT_FOUND" }, meta } }),
  } as unknown as ApiClient;
}

describe("customer rewards and standing screens", () => {
  afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  it("anchors ban remaining time to meta.serverTime and shows API standing values", async () => {
    const api = apiFor("/api/v1/customer/account-standing", {
      consecutiveNoShows: 1,
      penaltyStage: 2,
      currentThreshold: 3,
      currentBan: {
        banId: "ban-1",
        startsAt: "2026-11-16T10:00:00Z",
        endsAt: "2026-11-18T12:30:00Z",
        penaltyStage: 2,
      },
      verifiedPlayer: {
        qualification: "NOT_QUALIFIED",
        completedBookings: 6,
        requiredBookings: 10,
        status: "NOT_VERIFIED",
      },
    });

    render(<AccountStandingScreen api={api} onBack={jest.fn()} />);

    expect(await screen.findByText("6 / 10 completed bookings", {}, { timeout: 10_000 })).toBeTruthy();
    expect(screen.getByText("Not verified")).toBeTruthy();
    expect(screen.getByText("1 day 2 hours remaining")).toBeTruthy();
    expect(screen.getByText("1 / 3 consecutive no-shows")).toBeTruthy();
    expect(remainingBanText("2026-11-17T10:00:00Z", "2026-11-18T12:30:00Z")).toBe("1 day 2 hours remaining");
  }, 15_000);

  it("re-fetches standing when the active ban reaches its server-anchored end", async () => {
    jest.useFakeTimers();
    const get = jest.fn()
      .mockResolvedValueOnce({ data: { data: {
        consecutiveNoShows: 0,
        penaltyStage: 1,
        currentThreshold: 2,
        currentBan: {
          banId: "ban-1",
          startsAt: "2026-11-16T10:00:00Z",
          endsAt: "2026-11-17T10:00:01Z",
          penaltyStage: 1,
        },
        verifiedPlayer: {
          qualification: "NOT_QUALIFIED",
          completedBookings: 1,
          requiredBookings: 10,
          status: "NOT_VERIFIED",
        },
      }, meta } })
      .mockResolvedValue({ data: { data: {
        consecutiveNoShows: 0,
        penaltyStage: 1,
        currentThreshold: 2,
        currentBan: null,
        verifiedPlayer: {
          qualification: "NOT_QUALIFIED",
          completedBookings: 1,
          requiredBookings: 10,
          status: "NOT_VERIFIED",
        },
      }, meta: { ...meta, serverTime: "2026-11-17T10:00:01Z" } } });

    render(<AccountStandingScreen api={{ GET: get } as unknown as ApiClient} onBack={jest.fn()} />);
    await act(async () => {});
    expect(screen.getByText("Account temporarily banned")).toBeTruthy();

    await act(async () => {
      jest.advanceTimersByTime(1_000);
      await Promise.resolve();
    });
    await act(async () => {});

    expect(get).toHaveBeenCalledTimes(2);
    expect(screen.getByText("Account active")).toBeTruthy();
  });

  it("bounds expiry refreshes while the server still reports the expired ban", async () => {
    jest.useFakeTimers();
    let responseNumber = 0;
    const get = jest.fn(async () => {
      const serverTime = new Date(Date.parse("2026-11-17T10:00:00Z") + responseNumber * 1_000).toISOString();
      responseNumber += 1;
      return { data: { data: {
        consecutiveNoShows: 0,
        penaltyStage: 1,
        currentThreshold: 2,
        currentBan: {
          banId: "ban-still-active",
          startsAt: "2026-11-16T10:00:00Z",
          endsAt: "2026-11-17T09:59:00Z",
          penaltyStage: 1,
        },
        verifiedPlayer: {
          qualification: "NOT_QUALIFIED",
          completedBookings: 1,
          requiredBookings: 10,
          status: "NOT_VERIFIED",
        },
      }, meta: { ...meta, serverTime } } };
    });

    render(<AccountStandingScreen api={{ GET: get } as unknown as ApiClient} onBack={jest.fn()} />);
    await act(async () => {});
    await act(async () => {
      jest.advanceTimersByTime(1_500);
      await Promise.resolve();
    });

    expect(get).toHaveBeenCalledTimes(2);

    await act(async () => {
      jest.advanceTimersByTime(58_500);
      await Promise.resolve();
    });
    await act(async () => {});

    expect(get).toHaveBeenCalledTimes(3);
  });

  it("re-fetches standing when the app returns to the foreground", async () => {
    let appStateListener: ((state: string) => void) | undefined;
    jest.spyOn(AppState, "addEventListener").mockImplementation((_event, listener) => {
      appStateListener = listener as (state: string) => void;
      return { remove: jest.fn() };
    });
    const api = apiFor("/api/v1/customer/account-standing", {
      consecutiveNoShows: 0,
      penaltyStage: 0,
      currentThreshold: 3,
      currentBan: null,
      verifiedPlayer: {
        qualification: "NOT_QUALIFIED",
        completedBookings: 0,
        requiredBookings: 10,
        status: "NOT_VERIFIED",
      },
    });

    render(<AccountStandingScreen api={api} onBack={jest.fn()} />);
    await act(async () => {});
    expect(api.GET).toHaveBeenCalledTimes(1);

    await act(async () => {
      appStateListener?.("active");
      await Promise.resolve();
    });
    expect(api.GET).toHaveBeenCalledTimes(2);
  });

  it("shows points fee rules from the API, hides only zero compensation, and links booking rows", async () => {
    const openBooking = jest.fn();
    const api = apiFor("/api/v1/customer/points", {
      balance: 1200,
      feeUnit: { bookingFeeMode: "PER_HOUR", pointsPerFeeUnit: 10000 },
      nextCursor: null,
      items: [
        { transactionId: "p-0", amount: 0, balanceAfter: 1200, cause: "COMPENSATION", bookingId: null, occurredAt: "2026-11-17T09:00:00Z" },
        { transactionId: "p-1", amount: -10000, balanceAfter: 1200, cause: "BOOKING_FEE_PAYMENT", bookingId: "booking-1", occurredAt: "2026-11-17T08:00:00Z" },
        { transactionId: "p-2", amount: 0, balanceAfter: 1200, cause: "ADMIN", bookingId: null, occurredAt: "2026-11-17T07:00:00Z" },
      ],
    });

    render(<PointsScreen api={api} onBack={jest.fn()} onOpenBooking={openBooking} />);

    expect(await screen.findByText("1,200 points")).toBeTruthy();
    expect(screen.getByText("10,000 points pay one hour of the booking fee automatically. Half an hour uses half that amount.")).toBeTruthy();
    expect(screen.queryByText("Compensation")).toBeNull();
    expect(screen.getByText("Platform adjustment")).toBeTruthy();
    fireEvent.press(screen.getByRole("button", { name: "Open booking booking-1" }));
    expect(openBooking).toHaveBeenCalledWith("booking-1");
  });

  it("shows the wallet as read-only and derives plus/minus from the cause", async () => {
    const openBooking = jest.fn();
    const api = apiFor("/api/v1/customer/wallet", {
      balance: { amount: "0.00", currency: "EGP" },
      nextCursor: null,
      items: [
        { transactionId: "w-1", amount: { amount: "20.00", currency: "EGP" }, balanceAfter: { amount: "20.00", currency: "EGP" }, cause: "BOOKING_FEE_REFUND", bookingId: "booking-1", occurredAt: "2026-11-16T08:00:00Z" },
        { transactionId: "w-2", amount: { amount: "20.00", currency: "EGP" }, balanceAfter: { amount: "0.00", currency: "EGP" }, cause: "BOOKING_FEE_PAYMENT", bookingId: "booking-2", occurredAt: "2026-11-17T08:00:00Z" },
      ],
    });

    render(<WalletScreen api={api} onBack={jest.fn()} onOpenBooking={openBooking} />);

    expect(await screen.findByText("0.00 EGP")).toBeTruthy();
    expect(screen.getByText("+20.00 EGP")).toBeTruthy();
    expect(screen.getByText("−20.00 EGP")).toBeTruthy();
    expect(screen.getByText("Points pay first. The wallet automatically pays what remains of a later booking fee only when it covers all of that remainder. It never pays the venue gaming cost.")).toBeTruthy();
    expect(screen.queryByText(/top up|withdraw|pay with wallet/iu)).toBeNull();
  });

  it("shows the required zero-wallet empty state clearly", async () => {
    const api = apiFor("/api/v1/customer/wallet", {
      balance: { amount: "0.00", currency: "EGP" },
      nextCursor: null,
      items: [],
    });
    render(<WalletScreen api={api} onBack={jest.fn()} />);

    expect(await screen.findByText("0.00 EGP")).toBeTruthy();
    expect(screen.getByText("No wallet transactions yet")).toBeTruthy();
    expect(screen.getByText("Booking-fee refunds and automatic booking-fee payments will appear here.")).toBeTruthy();
  });

  it("loads the next points page from the server cursor", async () => {
    const get = jest.fn(async (_path: string, options: { params?: { query?: { cursor?: string } } }) => {
      const second = options.params?.query?.cursor === "next-1";
      return { data: { data: {
        balance: 200,
        feeUnit: { bookingFeeMode: "PER_BOOKING", pointsPerFeeUnit: 10000 },
        nextCursor: second ? null : "next-1",
        items: second
          ? [{ transactionId: "p-2", amount: 200, balanceAfter: 200, cause: "ATTENDED_COMPLETION", bookingId: "booking-2", occurredAt: "2026-11-17T08:00:00Z" }]
          : [],
      }, meta } };
    });
    render(<PointsScreen api={{ GET: get } as unknown as ApiClient} onBack={jest.fn()} />);

    const loadMore = await screen.findByRole("button", { name: "Load more" });
    expect(screen.queryByText("No points transactions yet")).toBeNull();
    fireEvent.press(loadMore);
    expect(await screen.findByText("Completed booking reward")).toBeTruthy();
    expect(get).toHaveBeenCalledTimes(2);
  });

  it("shares only the referral code as text and shows anonymous API statuses", async () => {
    const share = jest.spyOn(Share, "share").mockResolvedValue({ action: Share.sharedAction });
    const api = apiFor("/api/v1/customer/referrals", {
      referralCode: "K7M3QX9P",
      nextCursor: null,
      items: [{ referralId: "r-1", signedUpAt: "2026-11-17T08:00:00Z", rewardedAt: null, rewardPoints: null, status: "PENDING" }],
    });

    render(<ReferralsScreen api={api} onBack={jest.fn()} />);
    expect(await screen.findByText("K7M3QX9P")).toBeTruthy();
    expect(screen.getByText("Pending")).toBeTruthy();
    fireEvent.press(screen.getByRole("button", { name: "Share referral code" }));

    await waitFor(() => expect(share).toHaveBeenCalledWith({ message: "K7M3QX9P" }));
    expect(JSON.stringify(share.mock.calls[0])).not.toContain("http");
  });

  it("shows feedback when the native share sheet cannot be opened", async () => {
    jest.spyOn(Share, "share").mockRejectedValue(new Error("unavailable"));
    const api = apiFor("/api/v1/customer/referrals", {
      referralCode: "K7M3QX9P",
      nextCursor: null,
      items: [],
    });

    render(<ReferralsScreen api={api} onBack={jest.fn()} />);
    fireEvent.press(await screen.findByRole("button", { name: "Share referral code" }));

    expect(await screen.findByText("The share sheet could not be opened. Try again.")).toBeTruthy();
  });
});
