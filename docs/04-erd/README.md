# ERD — the E7GZ PS data model

Generated from `docs/02-architecture/04 — Data Model & Database Design.md`.
**63 tables · 19 modules · 11 append-only · 3 exclusion constraints.**

| File | Open it with | Use it for |
|---|---|---|
| `e7gz-schema.html` | any browser — double-click it | **Start here.** Browsable schema: the layer map, an ER diagram per module, and a card per table with every key column, its type, its notes and its constraints. Search across tables, columns and constraints; click any `FK` badge to jump to the referenced table. |
| `E7GZ-PS.dbml` | [dbdiagram.io](https://dbdiagram.io) → *New diagram → Import → paste* | The interactive, pan-and-zoom ERD. Also exports to PNG, PDF and PostgreSQL DDL. |
| `diagrams/*.svg` | any browser or image viewer | Static ER diagram per module, plus `overview.svg` for the core tables. Drop them into a slide or a doc. |

## How to read it

Four markers carry a product guarantee. They are not documentation — they are the reason the product is trustworthy, and none of them can be relaxed without a Product Change Request.

| Marker | Means |
|---|---|
| **exclusion constraint** | A database-level guarantee. `resource_reservation` cannot hold two overlapping active reservations on one resource; `booking` cannot hold two overlapping online commitments for one customer; `venue_operating_session` cannot hold two overlapping sessions for one venue. |
| **append-only** | `UPDATE` and `DELETE` are revoked and a trigger raises. Corrections are new rows. Eleven tables. |
| **SNAP** | A historical snapshot. The value was resolved once and is never recomputed from current configuration — prices, fees, rewards, console type, session bounds. |
| **projection** | Rebuildable from the system of record, and never a source of truth. Three tables. |

## Keeping it honest

Do not hand-edit these files as the schema evolves. The order is:

1. change the Flyway migration,
2. run it,
3. regenerate the ERD from the live database — DBeaver reverse-engineers it, or `dbdiagram.io` imports the DDL.

An ERD maintained by hand is wrong within two sprints. One generated from the database cannot be.

## Column coverage

Every table shows its primary key, foreign keys, status and enum columns, constraint-bearing columns, and the business-critical ones. Routine `created_at` / `updated_at` audit columns are omitted for readability — architecture doc 04 §5 is the full definition, and the migration is the truth.
