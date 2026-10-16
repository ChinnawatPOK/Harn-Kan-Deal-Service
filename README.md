init reo

## REST authentication

Deal Service reads `deal.jwt-secret` from `src/main/resources/application.yaml`. Keep it identical to the HS256 secret used by User Service and Payment Service (at least 32 characters). Docker Compose uses this application configuration directly.

Every REST request requires `Authorization: Bearer <token>`. Deal Service checks the HS256 signature, expiration, and a string `sub` claim; missing or invalid tokens return HTTP 401. This applies to all `/api/deals` endpoints. gRPC is separate and remains unauthenticated. The REST handlers still accept creator, participant, and requester IDs from request data; this change does not check them against the token subject.

## Database migrations

Flyway runs `src/main/resources/db/migration/V1__create_deals.sql` at startup and records the version in `flyway_schema_history`. For an existing database created by the old `schema.sql`, Flyway baselines at version 0, then applies V1 without deleting rows. `CREATE TABLE IF NOT EXISTS` preserves an existing `deals` table but does not alter its columns; review that table before relying on the baseline. Put future schema changes in new versioned migration files.
