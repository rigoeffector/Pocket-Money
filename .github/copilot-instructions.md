# Copilot Instructions (Pocket Money + USSD)

This document helps GitHub Copilot work effectively in this repository.

## Project Overview
- **Pocket Money API**: Spring Boot backend (Java)
  - Build tool: Gradle (wrapper included)
  - Java: 17 (toolchain enforced)
  - Database: PostgreSQL 12+
  - Default port: 8383
- **USSD Service**: Go (Fiber) monorepo under `ussd/`
  - Primary service: `ussd/services/ussd-service` (port 9000)
  - Shared code: `ussd/libs/shared-package`

## Key Paths (Pocket Money)
- Application entry: `src/main/java/com/pocketmoney/pocketmoney/PocketmoneyApplication.java`
- Controllers: `src/main/java/com/pocketmoney/pocketmoney/controller/`
- Services: `src/main/java/com/pocketmoney/pocketmoney/service/`
- JPA entities: `src/main/java/com/pocketmoney/pocketmoney/entity/`
- Repositories: `src/main/java/com/pocketmoney/pocketmoney/repository/`
- Config: `src/main/resources/application*.properties`
- Migrations: `all_migrations_consolidated.sql` and individual `*.sql` files

## Key Paths (USSD)
- API routes: `ussd/services/ussd-service/routes/api.go`
- Controllers: `ussd/services/ussd-service/controller/`
- Models: `ussd/services/ussd-service/model/`
- Config/bootstrap: `ussd/services/ussd-service/config/`
- Templates: `ussd/services/ussd-service/templates/`
- App entrypoint: `ussd/services/ussd-service/main.go`
- Example config: `ussd/services/ussd-service/config.yml.example`

## Build and Run (Pocket Money)
- Development run:
  ```bash
  ./gradlew bootRun --args='--spring.profiles.active=dev'
  ```
- Build JAR:
  ```bash
  ./gradlew bootJar
  ```
- Run JAR:
  ```bash
  java -jar build/libs/pocketmoney-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
  ```

## Database Migrations (Pocket Money)
- Local migration file: `all_migrations_consolidated.sql`
- Run with:
  ```bash
  psql -U postgres -d pocketmoney_db -f all_migrations_consolidated.sql
  ```
- Category setup scripts:
  - `add_gasoline_diesel_categories.sql`
  - `add_efashe_category.sql`

## USSD Runtime Configuration
- Viper loads `config.yml` and `ussd_config.json`.
- Redis and Postgres connection details come from `config.yml`.
- Default listen address is `0.0.0.0:9000`.

## USSD Workflow and Config
- Workflow steps live in `ussd/services/ussd-service/ussd_config.json` and are loaded by Viper in `main.go`.
- Alternative configs exist for different campaign states:
  - `ussd/services/ussd-service/ussd_config.main.json` (full flow)
  - `ussd/services/ussd-service/ussd_config.closed.json` (campaign closed message)
- The loader normalizes steps into Viper keys: `steps.<id>` for lookup in controllers.
- Step schema (per JSON):
  - `id`, `content`, `inputs[]`, `allow_back`, `validation`, `is_end_session`.
  - `inputs[]` fields: `input`, `value`, `action`, `next_step`, `validation`.
  - Optional: `content_type: "dynamic"` with `content` ending in `:fn` for dynamic content.
- The flow engine lives in `ussd/services/ussd-service/controller/Home.go`:
  - `processUSSD()` reads `steps.<id>` and routes to the next step.
  - Actions are dispatched via `callUserFunc()`; register new action names in its map.
  - Input validation hooks are optional and should be wired in the validation map in `processUSSD()`.
- Translations are defined in `ussd/services/ussd-service/locales/ussd.en.toml` and `ussd/services/ussd-service/locales/ussd.sw.toml`.
  - When `use_translation_keys` is true, `content` values should be i18n keys.
  - Language choice is stored on the session/customer and used by the localizer.

## Copilot Guidance
- Prefer existing patterns in `service/`, `controller/`, and `repository/` for the Java API.
- Keep Spring Boot annotations consistent with current style.
- Preserve Fiber middleware order and existing routing structure for the USSD service.
- Prefer shared utilities from `ussd/libs/shared-package` when available.
- Avoid adding new dependencies without a clear need.
- Do not include secrets in code or docs; rely on env variables and property placeholders.
- When editing SQL, keep statements idempotent when possible.

## Testing
- Java tests:
  ```bash
  ./gradlew test
  ```
- USSD tests (run from `ussd/services/ussd-service`):
  ```bash
  go test ./...
  ```

## Contribution Hints
- Use feature branches and small, focused PRs.
- Update docs in `docs/` when behavior changes.
- Prefer `application-dev.properties` for local defaults; production should use env vars.
- Keep changes scoped to relevant service folders; avoid cross-service edits unless required.
