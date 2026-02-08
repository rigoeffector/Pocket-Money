# Getting Started (No Spring Boot Experience Needed)

This guide helps you run the Pocket Money backend locally and start contributing.

## 1) Prerequisites
Install the following:
- **Java 17 (JDK)**
- **PostgreSQL 12+**
- **Git**

Confirm versions:
```bash
java -version
psql --version
```

## 2) Clone the Project
```bash
git clone <repository-url>
cd Pocket-Money
```

## 3) Create the Database
Create a local database (default name used by dev profile):
```bash
psql -U postgres -c "CREATE DATABASE pocketmoney_db;"
```

## 4) Run Database Migrations
Apply the consolidated migrations file:
```bash
psql -U postgres -d pocketmoney_db -f all_migrations_consolidated.sql
```

If PostgreSQL is running in Docker, run the migration from inside the container:
```bash
docker ps
docker exec -it <postgres_container_name> psql -U postgres -d pocketmoney_db -f /all_migrations_consolidated.sql
```

If the SQL file is on your host, copy it into the container first:
```bash
docker cp all_migrations_consolidated.sql <postgres_container_name>:/all_migrations_consolidated.sql
```

Optional: add required payment categories:
```bash
psql -U postgres -d pocketmoney_db -f add_gasoline_diesel_categories.sql
psql -U postgres -d pocketmoney_db -f add_efashe_category.sql
```

## 5) Configure Local Settings
Local defaults live in:
- `src/main/resources/application-dev.properties`
- `src/main/resources/application.properties`

If your local DB user/password differ, update `application-dev.properties` or set environment variables.

## 6) Run the App
Start the app with the dev profile:
```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

The API will be available at:
```
http://localhost:8383
```

## 7) Verify It Works
If actuator is enabled, try:
```
http://localhost:8383/actuator/health
```

You can also test login endpoints (see [README.md](../README.md)).

## 8) Common Tasks
- **Build JAR**:
  ```bash
  ./gradlew bootJar
  ```
- **Run tests**:
  ```bash
  ./gradlew test
  ```

## 9) Contribution Guide (Quick)
1. Create a branch: `feature/<short-name>`
2. Make small, focused changes.
3. Run tests if you touched business logic.
4. Update docs in `docs/` if you changed behavior or setup steps.

## 10) Useful Files
- API references: `EFASHE_API_REQUEST_BODIES.md`, `EFASHE_COMPLETE_FLOW.md`
- Deployment scripts: `deploy.sh`, `apply_migrations_remote.sh`
- SQL tools: `*.sql` files in repo root

## Notes About Secrets
Do **not** commit credentials or tokens. Prefer environment variables for secrets in production.
