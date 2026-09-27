# infra/

Deployment, backups and monitoring configuration (arch 12).

- `local/` — **E7-10** — files used by `docker-compose.yml` and the tests: PostgreSQL init script (roles + empty
  `e7gz_ps` database) and the SeaweedFS S3 credentials. Local development only.
- **E7-101** — Deployment, backup and restore drill (staging + production).
- **E7-100** — Observability, alerts and abuse signals (`infra/monitoring/`).
- **E7-109** — Store and web release readiness (dashboards online).

Owner: Sayed.
