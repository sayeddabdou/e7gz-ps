Design a complete, production-ready:

# TECHNICAL ARCHITECTURE
# BACKEND DESIGN
# DATABASE DATA MODEL
# API ARCHITECTURE

for a product called:

# E7GZ PS

The Product Functional Plan is already FINAL and APPROVED.

The Product contains THREE applications:

1. Customer Mobile App
2. Venue Web Dashboard
3. E7GZ PS Platform Admin

Do NOT redesign Product functionality.

Do NOT invent new Product features.

Translate the approved Product behavior into a scalable technical implementation.

---

# 1. REQUIRED ARCHITECTURAL APPROACH

Use an initial:

# MODULAR MONOLITH

with strong Domain boundaries.

Use internal Domain Events and design the modules so they may later be extracted into Microservices if required.

Do NOT create unnecessary Microservices for the first release.

---

# 2. RECOMMENDED STACK

Use:

### Customer App
React Native + TypeScript

### Venue Dashboard
React + TypeScript

### Platform Admin
React + TypeScript

### Backend
Java LTS + Spring Boot

### Database
PostgreSQL

### Cache / Coordination
Redis

### Async Messaging
RabbitMQ

### Media
S3-compatible Object Storage

### APIs
REST + JSON + OpenAPI

### Reliable Async Events
Transactional Outbox Pattern

---

# 3. CREATE THE COMPLETE SYSTEM ARCHITECTURE

Show:

Customer Mobile App

Venue Dashboard

Platform Admin

Backend

PostgreSQL

Redis

RabbitMQ

Object Storage

Push Notifications

SMS

Email

Maps Provider

Future Payment Gateway

Clearly explain the responsibilities of every component.

---

# 4. DEFINE BACKEND DOMAIN MODULES

Create detailed modules for:

### Identity

### Users

### Areas

### Venues

### Resources

### Pricing

### Availability

### Bookings

### Reviews

### Tournaments

### Trust / No-show

### Wallet

### Points / Loyalty

### Referrals

### Notifications

### Platform Configuration

### Audit

### Platform Administration

For each module explain:

- Responsibilities
- Main entities
- Services
- Domain events
- Dependencies
- APIs

---

# 5. IDENTITY TYPES

Support:

### Customer

### Venue Account

### Platform Admin

A Venue has one shared Venue Account.

Owner Password is:

# NOT A SEPARATE ACCOUNT.

It is a protected secret used for sensitive Venue actions.

---

# 6. OWNER PASSWORD

Store Owner Password only as:

# HASH

Never plain text.

Owner Password protects:

- Price changes
- Price Overrides
- Cancel Confirmed Booking
- Disable Resource
- Re-enable Resource
- Archive Resource
- Restore Resource

Owner Password does NOT protect:

- Maintenance
- Opening Hours
- Normal Accept / Reject
- Manual Booking
- No-show marking

Changing Owner Password must invalidate the old Password immediately.

---

# 7. DATABASE PRINCIPLES

Use:

- UUID IDs
- Foreign Keys
- UTC timestamps
- Numeric money types
- Explicit statuses
- Historical transaction values
- Append-only history
- Soft archival where history is required

Do NOT recalculate historical transactions from current configuration.

---

# 8. DESIGN THE COMPLETE DATABASE MODEL

At minimum define tables for:

### user_account

### otp_challenge

### user_trust_state

### user_ban

### area

### venue

### venue_image

### venue_activity

### venue_account

### venue_opening_day

### resource

### venue_default_price

### resource_price_override

### resource_reservation

### booking

### booking_event

### maintenance_window

### venue_closure

### review

### review_moderation_event

### venue_rating_stats

### tournament

### tournament_resource

### tournament_participant

### wallet_account

### wallet_transaction

### user_points_account

### points_transaction

### referral

### notification

### notification_delivery

### platform_config

### audit_event

### outbox_event

For each table provide:

- Primary Key
- Foreign Keys
- Important Columns
- Data Types
- Status values
- Constraints
- Indexes
- Unique constraints

---

# 9. RESOURCE RESERVATION LEDGER

Create one central:

# RESOURCE RESERVATION LEDGER

It must represent blocking time from:

- Pending Booking
- Confirmed Booking
- Manual Booking
- Late Confirmation Hold
- Tournament Hold
- Maintenance
- Closure

The database must guarantee:

# NO OVERLAPPING ACTIVE RESERVATIONS FOR THE SAME RESOURCE.

Use PostgreSQL range / exclusion constraint concepts.

Do NOT rely only on application code.

---

# 10. BOOKING STATE MACHINE

Implement:

### Pending

### Awaiting Customer Confirmation

### Confirmed

### Active

### Completed

### Rejected

### Cancelled

### No-show

Create a separate:

# REASON CODE

system.

Examples:

VENUE_NO_RESPONSE

VENUE_REJECTED

USER_CANCELLED

CUSTOMER_DECLINED_LATE_CONFIRM

CUSTOMER_RECONFIRM_TIMEOUT

VENUE_OPENING_HOURS_CHANGED

REJECTED_AFTER_EXPIRY

---

# 11. BOOKING HISTORY

Create append-only:

# BOOKING EVENTS

History must preserve every lifecycle event.

Example:

Booking Created

→ Pending

→ Venue Response Expired

→ Auto Cancelled

→ Late Confirm Requested

→ Awaiting Customer Confirmation

→ Customer Accepted

→ Confirmed

Do NOT overwrite or delete earlier events.

---

# 12. NORMAL BOOKING TRANSACTION

Design an atomic Booking creation flow that:

1. Validates User
2. Validates Venue
3. Validates Resource
4. Checks Opening Hours
5. Checks 15-minute rule
6. Calculates historical Venue Price
7. Calculates historical Booking Fee
8. Locks / reserves Resource
9. Creates Pending Booking
10. Creates Booking Event
11. Sets 10-minute Venue response deadline
12. Commits atomically

If Resource conflict exists:

Booking creation must fail cleanly.

---

# 13. 10-MINUTE VENUE RESPONSE

Venue has:

# 10 MINUTES

to Accept or Reject.

There is:

# NO AUTO ACCEPT.

Persist the deadline in Database.

Do NOT depend on browser/mobile timers.

Use a Backend Worker for expiration.

At expiry:

- Cancel Booking
- Reason = Venue Did Not Respond
- Release Resource
- Append History
- Notify Customer
- Expose Booking in Missed / Expired Requests

---

# 14. LATE CONFIRM

Allow Venue to Late Confirm an expired Booking until original Start Time.

Flow:

- Verify cancellation reason = Venue No Response
- Verify current time before Booking start
- Re-check full Resource interval
- If unavailable → reject Late Confirm
- If available → create new temporary Resource Hold
- SAME Booking ID
- Status → Awaiting Customer Confirmation
- 5-minute Customer deadline

---

# 15. CUSTOMER LATE RESPONSE

Customer gets:

# 5 MINUTES

Accept:

→ Confirmed

Decline:

→ Cancelled

Reason:

Customer Declined Late Confirmation

Timeout:

→ Cancelled

Reason:

Customer Did Not Reconfirm in Time

Release Resource on Decline / Timeout.

---

# 16. MULTIPLE LATE CONFIRMATIONS

Late Confirmation state must be:

# PER BOOKING

not global per User.

Every Booking independently stores:

- Expiry timestamp
- Warning flag
- Status

Multiple active Late Confirmation requests for one User must work independently.

---

# 17. OPENING HOURS

Support:

# DEFAULT SCHEDULE + DAILY OVERRIDES

Each day may:

- Use Default
- Use Custom Hours
- Be Closed

Support closing after midnight.

---

# 18. OPENING HOURS UPDATE

When Venue updates Opening Hours:

check:

# ALL FUTURE ORDINARY BOOKINGS.

Affected:

- Pending Online
- Confirmed Online
- Manual Booking

Show affected Booking preview.

On Venue confirmation:

- Save Opening Hours
- Cancel affected Bookings
- Reason = Venue Opening Hours Changed
- Release Resources
- Append History
- Notify Online Customers

Do NOT apply this auto-cancel rule to Tournaments unless Product changes later.

---

# 19. MANUAL BOOKINGS

Manual Bookings use the SAME:

- Booking table
- Reservation system
- Availability Engine

Source:

# MANUAL

Customer User ID may be null.

Do NOT create a separate Manual calendar.

---

# 20. REVIEWS

Any registered User may review any Venue.

Completed Booking is NOT required.

Rules:

- 1–5 Stars mandatory
- Comment optional
- One logical Review per User per Venue
- User may Edit
- User may Delete
- Venue View Only
- Platform Admin may Hide / Remove / Restore

Only Active public Reviews count toward Venue Rating.

---

# 21. VENUE RATING

Design a scalable Rating aggregation mechanism.

Maintain:

- Average Rating
- Review Count

Do not count:

- Hidden
- Removed
- User-deleted Reviews

---

# 22. TOURNAMENTS

Support:

### Player Hosted

Creator must be Verified Player.

### Venue Hosted

No Verified Player requirement.

Activities:

- PlayStation
- Billiards
- Ping Pong

Capacity:

Minimum 8

Maximum 32

Maximum Resources:

4

---

# 23. PLAYSTATION TOURNAMENTS

Require:

### Single

or:

### Multiplayer

Store Play Mode with Tournament.

---

# 24. TOURNAMENT RESOURCE HOLD

When Tournament is created:

immediately create Reservation Holds for selected Resources.

The same Resources become unavailable for:

- Customer Booking
- Manual Booking

---

# 25. TOURNAMENT CHECKPOINT

Exactly:

# 1 HOUR BEFORE START

check active Participants.

If Participants >= 8:

continue.

If < 8:

- Cancel Tournament
- Release Resource Holds
- Notify Participants

Removing a Participant before checkpoint does not instantly cancel Tournament.

---

# 26. PRIVATE TOURNAMENT

Support:

- Invite Code
- Invite Link

Private Tournament does not appear in public discovery.

---

# 27. NO-SHOW ENGINE

Implement approved consecutive No-show stages:

Initial:

3 consecutive

→ 7-Day Ban

After return:

2 consecutive

→ 1-Month Ban

After return:

2 consecutive

→ 2-Month Ban

Recurring:

Every 3 consecutive

→ 2-Month Ban

Completed Booking resets:

# Current Consecutive Counter

but does NOT reset historical penalty stage.

---

# 28. VERIFIED PLAYER

Requirement:

# 10 COMPLETED BOOKINGS

Verified Player is required only for Player Tournament Creation.

Referral does NOT affect trust.

Reviews do NOT affect trust.

No-show does not create progress.

---

# 29. MANUAL BOOKING TRUST

Product rule is unresolved.

Do NOT automatically count Manual Booking toward Verified Player Progress.

Design architecture so this can later be changed through a Policy without database redesign.

---

# 30. TOURNAMENT TRUST

Tournament participation does NOT currently increment Verified Player Progress.

Keep it separate.

---

# 31. POINTS

Use a ledger.

Current rules:

Completed Booking:

+200

Referral Signup:

+1,000

No-show:

0

Cancelled:

0

Rejected:

0

Use idempotency so rewards cannot be duplicated.

---

# 32. WALLET

Use:

Wallet Account

+

Wallet Transaction Ledger

Wallet supports:

- Booking Fee Refund
- Future Points Conversion

Do NOT store Venue gaming revenue in Wallet.

---

# 33. REFERRALS

Store:

- Referrer
- Referred User
- Code
- Actual Reward Applied

Historical reward must remain unchanged even if global Referral Reward changes later.

---

# 34. GLOBAL BOOKING FEE

One global Fee.

Applied:

# PER BOOKING

Launch:

# EGP 0

Each Booking stores the Fee used at Booking creation.

Changing global Fee affects future Bookings only.

---

# 35. PLATFORM CONFIGURATION

Support configuration for:

- Booking Fee
- Completed Booking Points
- Referral Reward
- Verified Player Requirement
- Tournament Creation Fee
- Points-to-Wallet ratio

Core No-show rules should remain Product Rules rather than normal editable Admin values.

---

# 36. NOTIFICATIONS

Design:

### In-App

### Push

### SMS

### Email

Every Booking notification must contain:

# BOOKING ID

for exact deep linking.

Examples:

- Booking Confirmed
- Booking Rejected
- Auto Cancelled
- Late Confirmation
- Late Confirmation Expiring
- Confirmation Expired
- Opening Hours Cancellation
- No-show
- Ban
- Points
- Tournament

---

# 37. RELIABLE ASYNC EVENTS

Use:

# TRANSACTIONAL OUTBOX

Business state + Outbox Event must commit in the same PostgreSQL transaction.

Outbox Publisher sends events to RabbitMQ.

Do NOT directly depend on RabbitMQ availability during core Booking transaction.

---

# 38. AUDIT

Create immutable audit records for important actions:

- Price Change
- Owner Password Change
- Resource Disable
- Resource Enable
- Archive
- Restore
- Review Moderation
- Verified Player Moderation
- Global Fee Change
- Venue Account Disable

Never record plain passwords.

---

# 39. APIs

Use:

# REST + JSON

Base:

`/api/v1`

Generate:

# OpenAPI

Provide endpoint groups for:

Customer

Venue

Admin

For every endpoint specify:

- Method
- URL
- Authentication
- Request
- Response
- Validation
- Error codes

---

# 40. IDEMPOTENCY

Require idempotency for critical write operations such as:

- Create Booking
- Late Confirm response
- Wallet transactions
- Point rewards
- Referral rewards

Avoid duplicate transactions caused by network retries.

---

# 41. SECURITY

Design:

- HTTPS
- Password hashing
- JWT
- Refresh Tokens
- Token rotation
- OTP expiration
- OTP rate limiting
- Owner Password rate limiting
- Authorization
- Input validation
- Secret management
- Audit
- Secure PII handling

Backend authorization must enforce all permissions.

Do NOT depend on frontend hiding.

---

# 42. LOCALIZATION

Backend stores language-neutral:

# STATUS / REASON CODES

Frontends translate them.

Venue Dashboard supports:

English

Arabic RTL

Venue Account stores language preference.

Platform Admin remains English only.

---

# 43. OBSERVABILITY

Include:

- Structured Logs
- Correlation IDs
- Metrics
- Error Tracking
- Database Monitoring
- Queue Monitoring
- Notification Monitoring
- Booking Timeout Worker Monitoring
- Tournament Checkpoint Monitoring

---

# 44. DEPLOYMENT

Design deployment using:

- Docker
- Load Balancer / WAF
- Multiple Backend replicas
- PostgreSQL HA
- Redis
- RabbitMQ
- Object Storage
- Background Workers

Architecture should be Kubernetes-ready but Kubernetes is NOT mandatory for the first release.

---

# 45. ENVIRONMENTS

Create:

### Development

### Staging

### Production

Use separate:

- Databases
- Secrets
- Queues
- Storage namespaces
- API configuration

---

# 46. BACKUP & RECOVERY

Provide:

- PostgreSQL backups
- Point-in-Time Recovery
- Object Storage protection
- Restore testing
- Recovery procedure

---

# 47. PAYMENT READINESS

Payment is NOT active at Launch.

Design a future:

# PaymentProvider Interface

for:

- Global Booking Fee
- Tournament Fee
- Refund

Do NOT couple Booking logic to a specific payment provider.

---

# 48. REQUIRED OUTPUT — ARCHITECTURE

Produce:

1. High-Level Architecture Diagram
2. Component Diagram
3. Backend Module Diagram
4. Data Flow explanation
5. Event Flow explanation
6. Deployment Diagram

---

# 49. REQUIRED OUTPUT — DATABASE

Produce:

1. Complete Entity List
2. ERD
3. Table definitions
4. Primary Keys
5. Foreign Keys
6. Indexes
7. Unique Constraints
8. Status enums
9. Money data types
10. Time / timezone strategy
11. Reservation overlap constraint

---

# 50. REQUIRED OUTPUT — BOOKING ENGINE

Produce full sequence diagrams for:

### Normal Booking

### Venue Accept

### Venue Reject

### 10-minute Auto Cancel

### Late Confirm

### Customer Accept

### Customer Decline

### Customer Timeout

### Manual Booking

### Opening Hours Cancellation

### No-show

---

# 51. REQUIRED OUTPUT — TOURNAMENT ENGINE

Produce sequence diagrams for:

### Player Tournament Create

### Venue Tournament Create

### Join

### Remove Participant

### Private Invite

### 1-hour Checkpoint

### Auto Cancel

---

# 52. REQUIRED OUTPUT — TRUST ENGINE

Produce detailed logic for:

- Completed Booking
- Verified Player
- No-show
- Penalty Stage
- Ban
- Ban Expiry
- Counter Reset

---

# 53. REQUIRED OUTPUT — REVIEW ENGINE

Produce:

- Write Review
- Edit Review
- Delete Review
- Rating Recalculation
- Hide
- Remove
- Restore

---

# 54. REQUIRED OUTPUT — API CONTRACTS

Create detailed API contracts for:

- Customer Mobile App
- Venue Dashboard
- Platform Admin

Do NOT create endpoints for unsupported features.

---

# 55. REQUIRED OUTPUT — SECURITY

Create:

- Authentication Flow
- Authorization Matrix
- Owner Password Security Flow
- PII rules
- Audit requirements
- Rate limiting rules

---

# 56. REQUIRED OUTPUT — IMPLEMENTATION ROADMAP

Break implementation into practical phases.

Recommended:

Phase 1:
Identity + Venue + Resources + Booking + Availability

Phase 2:
Venue Operations + Opening Hours + Pricing

Phase 3:
Trust + No-show + Points + Wallet + Referral

Phase 4:
Tournaments

Phase 5:
Reviews

Phase 6:
Notifications + Audit + Hardening

---

# 57. CRITICAL NON-NEGOTIABLE RULES

The architecture MUST guarantee:

### No Resource Double Booking

### Same Booking ID for Late Confirmation

### Historical Booking Fee Integrity

### Historical Referral Reward Integrity

### Independent Late Confirmation Timers per Booking

### Append-only Booking History

### Owner Password Security

### Shared Manual / Online Inventory

### Tournament Resource Holds

### Correct No-show Stage Progression

### Platform Review Moderation

---

# 58. FEATURES THAT MUST NOT BE ADDED

Do NOT add:

- PC Gaming
- Kick Off
- Venue POS
- Inventory
- Payroll
- Food Ordering
- Staff Accounts
- Manager Accounts
- Tournament Brackets
- Tournament Scoring
- Leaderboards
- Elo
- Full Accounting
- Tax Accounting
- Multi-branch Venue Accounts

Do NOT invent Product behavior.

---

# 59. FINAL GOAL

The final technical design must be detailed enough that a development team can begin implementing:

# DATABASE

# BACKEND

# MOBILE APIs

# VENUE APIs

# ADMIN APIs

without making undocumented Product decisions.

The technical design must preserve all approved E7GZ PS Product behavior while remaining:

# SIMPLE

# RELIABLE

# SCALABLE

# AUDITABLE

# SECURE

The final technical architecture must use:

# E7GZ PS FINAL PRODUCT FUNCTIONAL PLAN v4.0

as the Product source of truth.