# E7GZ PS
## TECHNICAL ARCHITECTURE & DETAILED DATA MODEL v1.0

**Status:** Approved Technical Baseline  
**Source:** E7GZ PS Final Product Functional Plan v4.0  
**Scope:** Customer Mobile App + Venue Dashboard + Platform Admin

---

# 1. ARCHITECTURE OBJECTIVE

The technical architecture must support the complete approved E7GZ PS Product while guaranteeing:

- No Resource double booking
- Reliable Booking timers
- Historical data integrity
- Shared availability between Online and Manual Bookings
- Tournament Resource Holds
- Late Confirmation
- Reviews
- No-show / Ban logic
- Wallet / Points / Referral
- Arabic RTL for Venue Dashboard
- Auditable Owner-protected actions
- Future payment integration

The architecture must be scalable without introducing unnecessary infrastructure complexity during the first release.

---

# 2. ARCHITECTURAL STYLE

Use:

# MODULAR MONOLITH + EVENT-DRIVEN INTERNAL INTEGRATION

for the first production release.

The backend is one deployable Spring Boot application with strongly separated domain modules.

Recommended modules:

- Identity
- Users
- Areas
- Venues
- Resources
- Pricing
- Availability
- Bookings
- Reviews
- Tournaments
- Trust / No-show
- Loyalty
- Wallet
- Referrals
- Notifications
- Platform Configuration
- Audit
- Administration

Each module owns its logic and exposes internal services/events.

Do NOT create tightly coupled database logic between unrelated modules.

The architecture must remain ready for future extraction into Microservices if scale requires it.

---

# 3. RECOMMENDED TECHNOLOGY STACK

## Customer Mobile App

React Native  
TypeScript

Use a shared API client layer.

---

## Venue Dashboard

React  
TypeScript

Full internationalization support:

English LTR

Arabic RTL

---

## Platform Admin

React  
TypeScript

English only for current release.

---

## Backend

Java LTS

Spring Boot

Spring Security

Spring Data JPA / JDBC where appropriate

OpenAPI REST APIs

---

## Primary Database

# PostgreSQL

PostgreSQL is the source of truth for:

- Users
- Venues
- Resources
- Bookings
- Reservations
- Reviews
- Tournaments
- Wallet
- Points
- Referrals
- Trust state
- Configuration
- Audit

---

## Redis

Use Redis for:

- Short-lived caching
- Rate limiting
- OTP attempts
- Optional distributed coordination
- Frequently accessed Venue / Area reference data

Redis must NOT be the only source of truth for Booking state.

---

## Messaging

# RabbitMQ

Use for asynchronous domain events such as:

- Notifications
- Email
- SMS
- Push delivery
- Rating recalculation
- Audit processing
- Non-blocking secondary workflows

Use Transactional Outbox for reliable event publishing.

---

## Object Storage

S3-compatible Object Storage for:

- Venue images
- Future User avatars
- Other media

Do not store image binary data directly inside PostgreSQL.

---

# 4. HIGH-LEVEL ARCHITECTURE

Customer Mobile App

→ REST API

Venue Dashboard

→ REST API

Platform Admin

→ REST API

All clients communicate with:

# E7GZ PS Backend

Backend communicates with:

- PostgreSQL
- Redis
- RabbitMQ
- Object Storage
- SMS Provider
- Email Provider
- Push Notification Providers
- Maps Provider

Future:

- Payment Gateway

---

# 5. BACKEND MODULES

## Identity Module

Responsible for:

- Customer signup
- Login
- OTP
- Access tokens
- Refresh tokens
- Venue authentication
- Platform Admin authentication
- Password security

---

## User Module

Responsible for:

- User profile
- Contact details
- Account verification
- User status
- Account Standing display data

---

## Venue Module

Responsible for:

- Venue information
- Area
- Address
- Coordinates
- Images
- Activities
- Opening Hours
- Venue Account
- Language preference

---

## Resource Module

Responsible for:

- Rooms
- Pool Tables
- Ping Pong Tables
- Console type
- Resource status
- Archive / Restore
- Disable / Re-enable
- Maintenance

---

## Pricing Module

Responsible for:

- Venue default pricing
- PS4 / PS5
- Single / Multiplayer
- Billiards
- Ping Pong
- Resource overrides
- Historical price calculation at Booking creation

---

## Availability Module

Responsible for:

- Resource time conflicts
- Shared Online / Manual availability
- Pending Holds
- Late Confirmation Holds
- Tournament Holds
- Maintenance
- Closures

This module is critical.

---

## Booking Module

Responsible for:

- Booking creation
- Pending state
- Venue Accept / Reject
- 10-minute timeout
- Late Confirmation
- 5-minute Customer response
- User cancellation
- Opening Hours cancellation
- Active
- Completed
- No-show
- Booking History

---

## Review Module

Responsible for:

- Venue Reviews
- Rating
- Edit / Delete
- Admin moderation
- Venue Rating statistics

---

## Tournament Module

Responsible for:

- Player-hosted Tournament
- Venue-hosted Tournament
- Participants
- Invite Codes
- Resource Holds
- Minimum Player checkpoint
- Auto cancellation

---

## Trust Module

Responsible for:

- Verified Player Progress
- No-show consecutive counter
- Penalty Stage
- User Ban
- Account Standing

---

## Loyalty Module

Responsible for:

- Points
- Completed Booking rewards
- Referral rewards

---

## Wallet Module

Responsible for:

- Wallet balance
- Booking Fee refunds
- Future Points conversion

---

## Notification Module

Responsible for:

- In-app notifications
- Push notifications
- Email
- SMS
- Deep links / entity references

---

## Admin Module

Responsible for administrative operations across:

- Areas
- Venues
- Users
- Reviews
- Fees
- Platform Configuration

It must call domain services rather than directly bypassing domain rules.

---

## Audit Module

Responsible for recording:

- Owner-protected actions
- Review moderation
- Venue account actions
- Verified Player moderation
- Global configuration changes
- Resource status changes

---

# 6. IDENTITY TYPES

Use three primary authenticated identities:

## CUSTOMER

Mobile App User.

---

## VENUE_ACCOUNT

One shared login per Venue.

Owner Password is NOT another account.

---

## PLATFORM_ADMIN

Internal E7GZ administrator.

---

# 7. AUTHENTICATION

Use:

- Short-lived Access Token
- Refresh Token
- Secure password hashing
- Device/session tracking

Customer OTP verification must be persisted.

OTP itself should expire and should not be permanently stored in plain text.

---

# 8. OWNER PASSWORD SECURITY

Owner Password is a second protected secret attached to the Venue.

Store only:

# OWNER_PASSWORD_HASH

Never store Owner Password in plain text.

Protected actions include:

- Price changes
- Price Override changes
- Cancel Confirmed Booking
- Disable Resource
- Re-enable Resource
- Archive Resource
- Restore Resource

Maintenance and Opening Hours do NOT require Owner Password.

Apply:

- Rate limiting
- Failed attempt tracking
- Audit logging

Changing Owner Password invalidates the old password immediately.

---

# 9. DATABASE DESIGN PRINCIPLES

Use:

- UUID identifiers internally
- Human-readable Booking Code separately
- UTC timestamps in database
- Africa/Cairo conversion at UI level
- `NUMERIC` for money
- Never floating point for financial values
- Foreign keys
- Explicit status enums / validated codes
- Soft deletion / archival where history is required

Historical transactional values must be copied into transaction records.

Never calculate historical Booking values from current configuration.

---

# 10. CORE USER TABLE

## user_account

Important fields:

- id
- username
- phone
- email
- password_hash
- account_verified
- account_status
- preferred_language
- created_at
- updated_at

Unique:

- username
- phone
- email

---

# 11. OTP TABLE

## otp_challenge

Fields:

- id
- user_id
- delivery_method
- destination
- otp_hash
- expires_at
- attempts
- status
- created_at

---

# 12. AREA TABLE

## area

Fields:

- id
- name
- status
- created_at
- updated_at

Status:

ACTIVE

INACTIVE

Do not physically delete Areas referenced historically.

---

# 13. VENUE TABLE

## venue

Fields:

- id
- name
- area_id
- address
- latitude
- longitude
- phone
- status
- default_open_time
- default_close_time
- rating_average
- rating_count
- created_at
- updated_at

Venue status:

- ONBOARDING
- ACTIVE
- INACTIVE
- SUSPENDED

---

# 14. VENUE IMAGE TABLE

## venue_image

Fields:

- id
- venue_id
- object_key
- display_order
- active
- created_at

Store only Object Storage reference.

---

# 15. VENUE ACTIVITIES

## venue_activity

Fields:

- venue_id
- activity_type
- active

Activity types:

PLAYSTATION

BILLIARDS

PING_PONG

Unique:

venue_id + activity_type

---

# 16. VENUE ACCOUNT

## venue_account

Fields:

- id
- venue_id
- login_identifier
- password_hash
- owner_password_hash
- status
- preferred_language
- last_login_at
- created_at
- updated_at

One active Venue Account per Venue.

Language:

EN

AR

---

# 17. OPENING HOURS

Use:

## venue_opening_day

Fields:

- id
- venue_id
- day_of_week
- mode
- open_time
- close_time

Mode:

USE_DEFAULT

CUSTOM

CLOSED

Venue table stores default opening / closing times.

Support close time after midnight.

---

# 18. RESOURCE TABLE

## resource

Fields:

- id
- venue_id
- activity_type
- name
- resource_number
- console_type
- resource_subtype
- status
- created_at
- updated_at
- archived_at

Console type:

PS4

PS5

nullable for non-PlayStation.

Status:

ACTIVE

DISABLED

UNDER_MAINTENANCE

ARCHIVED

---

# 19. PRICING TABLES

## venue_default_price

Fields:

- id
- venue_id
- activity_type
- console_type
- play_mode
- hourly_price
- currency
- active
- valid_from

For PlayStation:

console_type + play_mode.

For Billiards / Ping Pong:

console_type and play_mode are null.

---

## resource_price_override

Fields:

- id
- resource_id
- play_mode
- hourly_price
- active
- valid_from
- created_at

---

# 20. SINGLE SHARED RESERVATION LEDGER

Create:

# resource_reservation

This table is the foundation of double-booking prevention.

Fields:

- id
- resource_id
- reservation_type
- source_entity_id
- source_entity_type
- start_at
- end_at
- status
- created_at
- released_at
- release_reason

Reservation types:

BOOKING

MANUAL_BOOKING

LATE_CONFIRM_HOLD

TOURNAMENT

MAINTENANCE

CLOSURE

Status:

ACTIVE

RELEASED

---

# 21. DATABASE-LEVEL DOUBLE BOOKING PROTECTION

Use PostgreSQL range-based overlap protection.

Conceptually:

Resource + Active Time Range

must never overlap another Active reservation.

Use database constraint / exclusion logic on:

resource_id

and:

time range

for ACTIVE reservations.

This means even if two Booking requests reach the backend at the exact same millisecond:

only one may successfully reserve the Resource.

Do NOT depend only on frontend validation.

---

# 22. BOOKING TABLE

## booking

Fields:

- id
- booking_code
- user_id nullable
- venue_id
- resource_id
- source
- activity_type
- console_type
- play_mode
- start_at
- end_at
- duration_minutes
- venue_price
- booking_fee
- total_price
- currency
- status
- reason_code
- venue_response_expires_at
- late_confirm_expires_at
- late_expiry_notification_sent
- manual_customer_name nullable
- manual_customer_phone nullable
- created_at
- updated_at
- completed_at
- cancelled_at

Source:

ONLINE

MANUAL

---

# 23. BOOKING STATUS

Current Booking status values:

PENDING

AWAITING_CUSTOMER_CONFIRMATION

CONFIRMED

ACTIVE

COMPLETED

REJECTED

CANCELLED

NO_SHOW

---

# 24. BOOKING REASON CODES

Examples:

USER_CANCELLED

VENUE_REJECTED

VENUE_NO_RESPONSE

CUSTOMER_DECLINED_LATE_CONFIRM

CUSTOMER_RECONFIRM_TIMEOUT

VENUE_OPENING_HOURS_CHANGED

VENUE_CANCELLED

RESOURCE_CONFLICT

REJECTED_AFTER_EXPIRY

Keep status and reason separate.

---

# 25. BOOKING HISTORY

## booking_event

Fields:

- id
- booking_id
- event_type
- previous_status
- new_status
- reason_code
- actor_type
- actor_id
- event_payload
- occurred_at

Booking History must be:

# APPEND-ONLY

Do not overwrite previous events.

---

# 26. NORMAL BOOKING CREATION TRANSACTION

Booking creation transaction:

1. Validate User
2. Validate Venue
3. Validate Activity
4. Validate Resource
5. Validate Opening Hours
6. Validate 15-minute minimum
7. Calculate historical Price
8. Calculate historical Booking Fee
9. Attempt Resource Reservation
10. If conflict → Reject
11. Insert Booking as PENDING
12. Set Venue response expiry = now + 10 minutes
13. Insert Booking Event
14. Commit
15. Publish BOOKING_CREATED event

All reservation-critical steps occur transactionally.

---

# 27. 10-MINUTE VENUE TIMER

Do NOT depend on JavaScript timers.

Persist:

venue_response_expires_at

A background worker checks expired Pending Bookings.

Use safe concurrent processing such as:

`FOR UPDATE SKIP LOCKED`

When expired:

- Booking → CANCELLED
- Reason → VENUE_NO_RESPONSE
- Release reservation
- Append Booking History
- Publish event
- Notify Customer
- Make Booking available in Missed / Expired Requests

---

# 28. LATE CONFIRM TRANSACTION

Venue requests Late Confirm:

1. Validate Booking was cancelled due to VENUE_NO_RESPONSE
2. Validate now < original start time
3. Recheck complete Resource period
4. Attempt new Resource Reservation
5. If conflict → fail
6. Booking → AWAITING_CUSTOMER_CONFIRMATION
7. Set late_confirm_expires_at = now + 5 minutes
8. Reset late warning flag
9. Append Event
10. Commit
11. Notify exact User / Booking ID

No new Booking record.

---

# 29. LATE CONFIRM CUSTOMER RESPONSE

## Accept

Validate:

- status = AWAITING_CUSTOMER_CONFIRMATION
- now < late_confirm_expires_at

Then:

→ CONFIRMED

Reservation remains Active.

---

## Decline

→ CANCELLED

Reason:

CUSTOMER_DECLINED_LATE_CONFIRM

Release reservation.

---

## Timeout

Worker detects:

late_confirm_expires_at <= now

Then:

→ CANCELLED

Reason:

CUSTOMER_RECONFIRM_TIMEOUT

Release reservation.

---

# 30. MULTIPLE LATE CONFIRMATIONS

Late Confirmation timer state lives on each Booking.

Never use one shared User timer.

Every Booking independently stores:

- late_confirm_expires_at
- late_expiry_notification_sent

This allows multiple simultaneous Late Confirmations.

---

# 31. OPENING HOURS UPDATE

Venue changes Opening Hours.

Backend must:

1. Validate new schedule
2. Find ALL future ordinary Bookings
3. Resolve weekday for every Booking
4. Determine whether Booking falls outside new schedule
5. Return affected Booking preview to Venue
6. Venue confirms change
7. Save schedule
8. Cancel affected Bookings
9. Release Reservations
10. Append Booking Events
11. Notify affected Online Customers

Cancellation reason:

VENUE_OPENING_HOURS_CHANGED

Do not apply this behavior to Tournaments unless a later Product rule defines it.

---

# 32. MAINTENANCE

## maintenance_window

Fields:

- id
- venue_id
- resource_id
- start_at
- end_at
- reason
- status
- created_at

Creating Maintenance also creates:

ACTIVE resource_reservation

type:

MAINTENANCE

No Owner Password required.

---

# 33. CLOSURES

## venue_closure

Fields:

- id
- venue_id
- start_at
- end_at
- full_day
- reason
- status
- created_at

To guarantee availability:

create Resource Reservation blocks for affected Resources.

---

# 34. REVIEWS

## review

Fields:

- id
- user_id
- venue_id
- stars
- comment
- moderation_status
- user_deleted_at
- created_at
- updated_at

Constraint:

# UNIQUE USER + VENUE

One logical Review per User per Venue.

Stars constraint:

1–5.

---

# 35. REVIEW MODERATION

## review_moderation_event

Fields:

- id
- review_id
- admin_id
- action
- reason
- previous_status
- new_status
- created_at

Status:

ACTIVE

HIDDEN

REMOVED

User-deleted Review is not publicly returned.

---

# 36. VENUE RATING AGGREGATE

Use:

## venue_rating_stats

or cached fields on Venue.

Fields:

- venue_id
- rating_average
- rating_count
- updated_at

Calculate using only:

ACTIVE

and not User-deleted Reviews.

Rating recalculation may be triggered asynchronously after Review changes.

---

# 37. TOURNAMENT TABLE

## tournament

Fields:

- id
- name
- host_type
- host_user_id nullable
- host_venue_id nullable
- activity_type
- play_mode nullable
- visibility
- description
- capacity
- minimum_players
- entry_amount
- prize_amount
- start_at
- end_at
- checkpoint_at
- status
- invite_code nullable
- created_at
- updated_at

Host type:

USER

VENUE

Visibility:

PUBLIC

PRIVATE

---

# 38. TOURNAMENT STATUS

WAITING_FOR_PLAYERS

CONFIRMED

ACTIVE

COMPLETED

CANCELLED

---

# 39. TOURNAMENT RESOURCES

## tournament_resource

Fields:

- tournament_id
- resource_id

Maximum:

# 4

At creation:

each Resource gets ACTIVE reservation for Tournament interval.

---

# 40. TOURNAMENT PARTICIPANTS

## tournament_participant

Fields:

- id
- tournament_id
- user_id
- role
- status
- joined_at
- removed_at

Unique:

tournament_id + user_id

Role:

ORGANIZER

PARTICIPANT

---

# 41. PLAYER TOURNAMENT ELIGIBILITY

For USER-hosted Tournament:

Creator must have:

# VERIFIED PLAYER ACTIVE

Venue-hosted Tournament does not require trust check.

---

# 42. TOURNAMENT CHECKPOINT

checkpoint_at:

# start_at - 1 hour

Worker processes WAITING_FOR_PLAYERS Tournaments.

If active participants >= 8:

Tournament continues.

If < 8:

- Tournament → CANCELLED
- Release Tournament Reservations
- Notify participants

Participant removal before checkpoint does not instantly cancel Tournament.

---

# 43. USER TRUST STATE

## user_trust_state

Fields:

- user_id
- verified_player
- verified_completed_count
- penalty_stage
- consecutive_no_show_count
- current_threshold
- current_ban_until
- updated_at

---

# 44. NO-SHOW EVENT

## no_show_event

Fields:

- id
- user_id
- booking_id
- venue_id
- sequence_count
- penalty_stage_before
- penalty_result
- created_at

---

# 45. BAN HISTORY

## user_ban

Fields:

- id
- user_id
- reason
- start_at
- end_at
- penalty_stage
- source_booking_id
- status
- created_at

---

# 46. NO-SHOW ENGINE

Initial stage:

threshold = 3

Penalty:

7 days

After first penalty:

threshold = 2

Penalty:

1 month

After second:

threshold = 2

Penalty:

2 months

After third:

Recurring stage

threshold = 3

Penalty:

2 months

Completed Booking:

consecutive counter → 0

Penalty stage remains.

---

# 47. POINTS LEDGER

Never store only a mutable number without history.

Use:

## points_transaction

Fields:

- id
- user_id
- transaction_type
- points
- source_entity_type
- source_entity_id
- description
- idempotency_key
- created_at

Examples:

BOOKING_COMPLETED

REFERRAL_REWARD

POINTS_CONVERSION

---

# 48. USER POINT BALANCE

Maintain:

## user_points_account

Fields:

- user_id
- balance
- updated_at

Balance is derived from / synchronized with ledger.

---

# 49. WALLET

## wallet_account

Fields:

- user_id
- balance
- currency
- updated_at

---

# 50. WALLET TRANSACTION

## wallet_transaction

Fields:

- id
- user_id
- type
- amount
- source_entity_type
- source_entity_id
- idempotency_key
- balance_after
- created_at

Examples:

BOOKING_FEE_REFUND

POINTS_CONVERSION

Do not store Venue gaming revenue here.

---

# 51. REFERRAL

## referral

Fields:

- id
- referrer_user_id
- referred_user_id
- referral_code
- reward_points_applied
- status
- created_at
- rewarded_at

Store:

# HISTORICAL REWARD VALUE

Never recalculate old rewards from current configuration.

---

# 52. PLATFORM CONFIGURATION

## platform_config

Fields:

- key
- value
- value_type
- updated_by
- updated_at

Examples:

BOOKING_FEE

COMPLETED_BOOKING_POINTS

REFERRAL_REWARD

VERIFIED_PLAYER_REQUIRED_BOOKINGS

TOURNAMENT_CREATION_FEE

POINTS_WALLET_CONVERSION_RATIO

Core Product Rules such as No-show thresholds should not necessarily be editable.

---

# 53. NOTIFICATIONS

## notification

Fields:

- id
- recipient_type
- recipient_id
- notification_type
- title_key
- body_key
- entity_type
- entity_id
- booking_id nullable
- tournament_id nullable
- read_at
- created_at

Every Booking notification should reference:

# EXACT BOOKING ID

for correct deep linking.

---

# 54. NOTIFICATION DELIVERY

## notification_delivery

Fields:

- id
- notification_id
- channel
- provider
- status
- attempted_at
- delivered_at
- error_code

Channels:

IN_APP

PUSH

SMS

EMAIL

---

# 55. AUDIT LOG

## audit_event

Fields:

- id
- actor_type
- actor_id
- venue_id nullable
- entity_type
- entity_id
- action
- before_data
- after_data
- reason
- ip_address
- created_at

Audit examples:

- Owner Price Change
- Resource Archive
- Resource Restore
- Venue Account Disabled
- Review Hidden
- Verified Player Revoked
- Global Booking Fee Changed

Never log plain-text passwords.

---

# 56. TRANSACTIONAL OUTBOX

## outbox_event

Fields:

- id
- aggregate_type
- aggregate_id
- event_type
- payload
- created_at
- published_at

Whenever a transaction needs an asynchronous event:

write the business data and Outbox event in the same PostgreSQL transaction.

Background publisher sends Outbox events to RabbitMQ.

This prevents losing Notifications after a successful database transaction.

---

# 57. CORE DOMAIN EVENTS

Examples:

USER_REGISTERED

USER_VERIFIED

BOOKING_CREATED

BOOKING_ACCEPTED

BOOKING_REJECTED

BOOKING_AUTO_CANCELLED

LATE_CONFIRM_REQUESTED

LATE_CONFIRM_ACCEPTED

LATE_CONFIRM_DECLINED

LATE_CONFIRM_EXPIRED

BOOKING_COMPLETED

NO_SHOW_RECORDED

USER_BANNED

BAN_EXPIRED

REVIEW_CREATED

REVIEW_UPDATED

REVIEW_DELETED

REVIEW_MODERATED

TOURNAMENT_CREATED

TOURNAMENT_MINIMUM_REACHED

TOURNAMENT_CANCELLED

POINTS_AWARDED

REFERRAL_REWARDED

---

# 58. API STYLE

Use:

# REST + JSON

Version APIs:

`/api/v1/...`

Generate:

# OpenAPI Specification

from backend contracts.

---

# 59. CUSTOMER API GROUPS

Examples:

`/api/v1/auth`

`/api/v1/users/me`

`/api/v1/areas`

`/api/v1/venues`

`/api/v1/venues/{id}/reviews`

`/api/v1/availability`

`/api/v1/bookings`

`/api/v1/bookings/{id}`

`/api/v1/bookings/{id}/late-confirm/accept`

`/api/v1/bookings/{id}/late-confirm/decline`

`/api/v1/tournaments`

`/api/v1/wallet`

`/api/v1/points`

`/api/v1/referrals`

`/api/v1/notifications`

---

# 60. VENUE API GROUPS

Examples:

`/api/v1/venue/me`

`/api/v1/venue/bookings`

`/api/v1/venue/bookings/{id}/accept`

`/api/v1/venue/bookings/{id}/reject`

`/api/v1/venue/bookings/{id}/late-confirm`

`/api/v1/venue/manual-bookings`

`/api/v1/venue/resources`

`/api/v1/venue/pricing`

`/api/v1/venue/opening-hours`

`/api/v1/venue/reviews`

`/api/v1/venue/tournaments`

`/api/v1/venue/history`

---

# 61. ADMIN API GROUPS

Examples:

`/api/v1/admin/areas`

`/api/v1/admin/venues`

`/api/v1/admin/venue-accounts`

`/api/v1/admin/users`

`/api/v1/admin/reviews`

`/api/v1/admin/bookings`

`/api/v1/admin/no-shows`

`/api/v1/admin/tournaments`

`/api/v1/admin/wallets`

`/api/v1/admin/points`

`/api/v1/admin/referrals`

`/api/v1/admin/config`

---

# 62. IDEMPOTENCY

Critical write APIs must support idempotency.

Examples:

- Booking Create
- Late Confirm Accept
- Wallet transaction
- Points reward
- Referral reward

Use:

# Idempotency-Key

where appropriate.

This prevents duplicated operations from mobile retries or network issues.

---

# 63. SECURITY

Apply:

- HTTPS only
- Secure password hashing
- JWT validation
- Refresh token rotation
- Rate limiting
- OTP attempt limits
- Input validation
- SQL parameterization
- Access control by identity type
- Owner Password rate limiting
- Audit logging
- Secure secrets management

---

# 64. DATA ACCESS RULES

Customer can access only their own:

- Bookings
- Wallet
- Points
- Account Standing
- Notifications

Venue can access only data belonging to its Venue.

Platform Admin has platform-wide administrative visibility.

Do not rely on frontend hiding.

Enforce authorization in Backend.

---

# 65. PII

Sensitive information includes:

- Phone
- Email
- Customer contact data

Access should be minimized.

Customer Phone becomes available to Venue only when Product rules allow it.

Protect sensitive data at rest and in transit.

---

# 66. LOCALIZATION

Backend should use:

# MESSAGE / REASON CODES

rather than storing translated UI sentences as business state.

Example:

`VENUE_NO_RESPONSE`

Frontend translates to:

English:

“Venue did not respond in time.”

Arabic:

“لم يقم المكان بالرد في الوقت المحدد.”

This keeps business logic language-neutral.

---

# 67. CUSTOMER MOBILE LANGUAGE

Customer App remains localization-ready.

---

# 68. VENUE LANGUAGE

Venue Account stores:

preferred_language

Values:

EN

AR

Venue Dashboard also stores Browser preference.

Account value is authoritative after login.

---

# 69. PLATFORM ADMIN LANGUAGE

Current Platform Admin UI:

# ENGLISH

Backend remains language-neutral.

---

# 70. OBSERVABILITY

Implement:

- Structured logs
- Request correlation ID
- Error monitoring
- Application metrics
- Database metrics
- Queue metrics
- Booking timer metrics
- Notification delivery metrics

Critical alerts:

- Booking worker failure
- RabbitMQ backlog
- PostgreSQL connection issues
- Reservation conflict anomalies
- Notification delivery failures

---

# 71. DEPLOYMENT MODEL

Recommended production topology:

Internet

→ Load Balancer / WAF

→ Backend Application Replicas

→ PostgreSQL

→ Redis

→ RabbitMQ

→ Object Storage

Separate worker deployment/profile for:

- Timers
- Notifications
- Outbox publishing
- Tournament checkpoint processing

The codebase may remain the same.

---

# 72. ENVIRONMENTS

Maintain:

### Development

### Staging

### Production

Never test production-changing Booking flows directly against Production data.

---

# 73. CONTAINERIZATION

Package backend and web applications as:

# Docker Containers

Architecture should be Kubernetes-ready but Kubernetes is not mandatory for first launch.

Avoid unnecessary operational complexity.

---

# 74. DATABASE HIGH AVAILABILITY

Production PostgreSQL should support:

- Automated backups
- Point-in-time recovery
- Replication / standby
- Monitoring

Booking data is business-critical.

---

# 75. BACKUP

Minimum design:

- Daily backup
- Continuous WAL / point-in-time recovery where supported
- Object Storage backup policy
- Restore testing

Do not consider backup complete until restore is tested.

---

# 76. PAYMENT READINESS

Payment is not active at Launch.

However architecture must support future:

- Payment Gateway
- Booking Fee charge
- Tournament Creation Fee
- Refund

Create future interface:

# PaymentProvider

Do not couple Booking business logic directly to a specific payment company.

---

# 77. PAYMENT HISTORY PRINCIPLE

Future payment data must also preserve historical values.

Never recalculate historical payment amounts from current fees.

---

# 78. UNRESOLVED MANUAL BOOKING TRUST RULE

The Product has NOT approved whether Manual Booking counts toward Verified Player Progress.

Architecture must therefore avoid hard-coding it.

Recommended:

Store an eligibility marker / evaluate through Loyalty Policy.

Default Manual Booking trust behavior remains disabled until Product decision.

---

# 79. UNRESOLVED TOURNAMENT TRUST RULE

Tournament participation is NOT currently approved as Verified Player Progress.

Do not increment trust progress from Tournament participation.

---

# 80. INITIAL IMPLEMENTATION PHASES

## Phase 1 — Core Platform

- Authentication
- Users
- Areas
- Venues
- Resources
- Pricing
- Availability
- Booking
- Shared Reservation Ledger

---

## Phase 2 — Venue Operations

- Venue Dashboard APIs
- Manual Booking
- Maintenance
- Opening Hours
- Owner Password
- Revenue queries

---

## Phase 3 — Trust & Loyalty

- Completed Booking
- No-show
- Ban Engine
- Verified Player
- Points
- Referral
- Wallet

---

## Phase 4 — Tournaments

- Tournament creation
- Participants
- Resource Holds
- Checkpoint worker
- Private Invite

---

## Phase 5 — Reviews

- Review CRUD
- Rating Aggregation
- Admin Moderation

---

## Phase 6 — Notifications & Hardening

- Push
- SMS
- Email
- Outbox
- Monitoring
- Audit
- Security hardening

---

# 81. CRITICAL ACCEPTANCE TESTS

Before Production launch verify:

### Booking Race Test

Two Users attempt the same Resource and time simultaneously.

Only ONE succeeds.

---

### Pending Timeout Test

Venue does not respond for 10 minutes.

Booking cancels and Resource releases.

---

### Late Confirm Test

Expired Booking is restored only if Resource remains available.

---

### Multiple Late Confirm Test

Two Late Confirmation requests for one User operate independently.

---

### Opening Hours Test

Changing Venue Hours finds all affected future ordinary Bookings.

---

### Tournament Hold Test

Tournament prevents normal / manual Booking on held Resources.

---

### No-show Stage Test

Penalty sequence correctly follows:

3 → 7 days

2 → 1 month

2 → 2 months

3 → 2 months recurring

---

### Review Moderation Test

Hidden / Removed Review does not affect public Rating.

---

### Historical Fee Test

Changing Global Booking Fee does not modify old Bookings.

---

### Owner Password Test

Changing Owner Password invalidates old Password for every protected action.

---

# 82. TECHNICAL BASELINE

The approved initial technical baseline is:

# React Native + TypeScript
Customer Mobile App

# React + TypeScript
Venue Dashboard / Platform Admin

# Java + Spring Boot
Backend

# PostgreSQL
Transactional Source of Truth

# Redis
Cache / Rate Limit / Short-Lived Coordination

# RabbitMQ
Asynchronous Events

# S3-Compatible Object Storage
Images

# REST / OpenAPI
Client Integration

# Transactional Outbox
Reliable Events

# Shared Resource Reservation Ledger
Availability / Double Booking Protection

---

# 83. FINAL TECHNICAL PRINCIPLE

The most important backend rule is:

# AVAILABILITY MUST BE GUARANTEED BY THE SERVER AND DATABASE.

Never trust:

- Client calendar state
- Frontend disabled buttons
- Cached availability alone

All Booking, Manual Booking, Tournament, Maintenance and Hold operations must pass through the central Availability Engine and database reservation constraint.

---

# 84. DOCUMENT STATUS

This document represents:

# E7GZ PS TECHNICAL ARCHITECTURE & DETAILED DATA MODEL v1.0

based on:

# FINAL PRODUCT FUNCTIONAL PLAN v4.0

and becomes the technical baseline for implementation.