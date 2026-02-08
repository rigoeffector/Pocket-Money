# Copilot Instructions (Pocket Money)

This document helps GitHub Copilot work effectively in this repository.

## Project Overview
- **Type**: Spring Boot backend (Java)
- **Build tool**: Gradle (wrapper included)
- **Java**: 17 (toolchain enforced)
- **Database**: PostgreSQL 12+
- **Default port**: 8383

## Key Paths
- Application entry: `src/main/java/com/pocketmoney/pocketmoney/PocketmoneyApplication.java`
- Controllers: `src/main/java/com/pocketmoney/pocketmoney/controller/`
- Services: `src/main/java/com/pocketmoney/pocketmoney/service/`
- JPA entities: `src/main/java/com/pocketmoney/pocketmoney/entity/`
- Repositories: `src/main/java/com/pocketmoney/pocketmoney/repository/`
- Config: `src/main/resources/application*.properties`
- Migrations: `all_migrations_consolidated.sql` and individual `*.sql` files

## Build and Run
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

## Database Migrations
- Local migration file: `all_migrations_consolidated.sql`
- Run with:
  ```bash
  psql -U postgres -d pocketmoney_db -f all_migrations_consolidated.sql
  ```
- Category setup scripts:
  - `add_gasoline_diesel_categories.sql`
  - `add_efashe_category.sql`

## Copilot Guidance
- Prefer existing patterns in `service/`, `controller/`, and `repository/`.
- Keep Spring Boot annotations consistent with current style.
- Avoid adding new dependencies without a clear need.
- Do not include secrets in code or docs; rely on env variables and property placeholders.
- When editing SQL, keep statements idempotent when possible.

## Test & Lint
- Tests:
  ```bash
  ./gradlew test
  ```

## Contribution Hints
- Use feature branches and small, focused PRs.
- Update docs in `docs/` when behavior changes.
- Prefer `application-dev.properties` for local defaults; production should use env vars.
