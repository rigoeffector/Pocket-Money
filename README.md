# Pocket Money

A Spring Boot application for managing payments, EFASHE services (airtime, RRA, TV, electricity, gasoline, diesel), NFC/MOMO payments, and merchant operations.

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Tech Stack](#tech-stack)
- [Quick Start](#quick-start)
- [Database Setup](#database-setup)
- [Configuration](#configuration)
- [Running the Application](#running-the-application)
- [API Overview](#api-overview)
- [Project Structure](#project-structure)
- [Useful Scripts](#useful-scripts)
- [Deployment](#deployment)
- [Troubleshooting](#troubleshooting)

---

## Prerequisites

- **Java 17** (JDK 17)
- **PostgreSQL** (12+)
- **Gradle** (or use the included `./gradlew` wrapper)

Check versions:

```bash
java -version    # Should be 17
psql --version   # PostgreSQL client (optional, for DB setup)
```

---

## Tech Stack

- **Spring Boot 4.x** – Web, Security, JPA, Validation
- **PostgreSQL** – Database
- **JWT** – Authentication (jjwt)
- **Apache PDFBox** – PDF generation (receipts, daily reports)
- **ZXing** – QR code generation

---

## Quick Start

1. **Clone the repository** (if not already):

   ```bash
   git clone <repository-url>
   cd pocketmoney
   ```

2. **Create the database** (see [Database Setup](#database-setup)).

3. **Run with dev profile**:

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=dev'
   ```

4. **Base URL**: `http://localhost:8383`

---

## Database Setup

### Local (development)

1. Install and start PostgreSQL.

2. Create database and user (if needed):

   ```bash
   psql -U postgres -c "CREATE DATABASE pocketmoney_db;"
   ```

3. Apply migrations (optional; JPA can create/update schema with `ddl-auto=update`):

   ```bash
   psql -U postgres -d pocketmoney_db -f all_migrations_consolidated.sql
   ```

4. Dev config uses:
   - URL: `jdbc:postgresql://localhost:5432/pocketmoney_db`
   - User: `postgres`
   - Password: set in `application-dev.properties` (or override with env vars)

### Payment categories

Ensure required payment categories exist (e.g. EFASHE, GASOLINE, DIESEL, PAY_CUSTOMER, QR Code). Use the SQL in:

- `add_gasoline_diesel_categories.sql`
- `add_efashe_category.sql`

Or run the corresponding `.sh` scripts for local/remote.

---

## Configuration

### Profiles

| Profile | File | Use case |
|--------|------|----------|
| **dev** | `application-dev.properties` | Local development, SQL logging |
| **prod** | `application-prod.properties` | Production (prefer env vars for secrets) |

Switch profile:

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Or set in `application.properties`:

```properties
spring.profiles.active=dev
```

### Main settings (dev)

- **Server port**: `8383`
- **Database**: PostgreSQL on `localhost:5432`, database `pocketmoney_db`
- **JWT**: Secret and expiration in properties (override with `JWT_SECRET` in prod)
- **EFASHE**: API URL and keys in `application.properties`
- **SMS / WhatsApp / MoPay / BizaoPayment**: In `application-dev.properties` (or prod with env vars)

### Environment variables (production)

Prefer env vars for production:

- `JWT_SECRET` – JWT signing key
- `SMS_API_KEY`, `SMS_API_URL`, `SMS_SENDER_ID`
- `WHATSAPP_API_KEY`
- `MOPAY_API_TOKEN`
- `BIZAOPAYMENT_API_URL`, `BIZAOPAYMENT_API_TOKEN`, `BIZAOPAYMENT_WEBHOOK_SIGNING_KEY`
- `SERVER_PORT` (default 8383)

---

## Running the Application

```bash
# Development (with dev profile)
./gradlew bootRun --args='--spring.profiles.active=dev'

# Build JAR (no profile; uses default in application.properties)
./gradlew bootJar

# Run JAR
java -jar build/libs/pocketmoney-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

Health/actuator (if enabled): `http://localhost:8383/actuator/health`

---

## API Overview

Base path: `http://localhost:8383` (or your deployed URL).

### Public (no auth)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/public/users/signup` | User signup |
| POST | `/api/public/receivers/signup` | Receiver/merchant signup |
| POST | `/api/public/users/login` | User login (returns JWT) |
| POST | `/api/public/receivers/login` | Receiver login (returns JWT) |
| POST | `/api/public/payments/pay/momo` | Public MOMO payment |

### Auth

Use the JWT from login in the `Authorization` header:

```http
Authorization: Bearer <your-jwt-token>
```

### Main API areas (protected)

- **Auth** – `/api/auth/*` (e.g. merchants, profile)
- **Payments** – `/api/payments/*` (transactions, admin income, dashboard)
- **EFASHE** – `/api/efashe/*` (initiate, process, status, transactions, refunds, receipt, electricity tokens)
- **EFASHE Settings** – `/api/efashe/settings/*`
- **Receivers** – `/api/receivers/*` (daily report, etc.)
- **Users** – `/api/users/*`
- **Payment categories** – `/api/payment-categories/*`
- **QR Code** – `/api/qrcode/*`

### EFASHE service types

- AIRTIME, MTN, RRA, TV, ELECTRICITY, GASOLINE, DIESEL  
- RRA uses a fixed charge table by amount; others may use percentage-based splits.

---

## Project Structure

```
pocketmoney/
├── src/main/java/com/pocketmoney/pocketmoney/
│   ├── PocketmoneyApplication.java     # Entry point
│   ├── config/                          # Security, CORS, data init
│   ├── controller/                      # REST controllers
│   ├── dto/                             # Request/response DTOs
│   ├── entity/                          # JPA entities
│   ├── repository/                      # JPA repositories
│   ├── service/                         # Business logic
│   └── security/                        # JWT filter, auth
├── src/main/resources/
│   ├── application.properties           # Base config + EFASHE
│   ├── application-dev.properties       # Dev profile
│   └── application-prod.properties     # Prod profile
├── build.gradle
├── all_migrations_consolidated.sql      # DB migrations
├── deploy.sh                            # Deploy to remote server
├── check_transaction.sh                 # Look up transaction on remote DB
└── README.md                            # This file
```

---

## Useful Scripts

| Script | Purpose |
|--------|--------|
| `./gradlew bootRun --args='--spring.profiles.active=dev'` | Run app with dev profile |
| `./gradlew bootJar` | Build executable JAR |
| `./gradlew compileJava` | Compile only |
| `./check_transaction.sh [transaction_id]` | Query transaction on remote server (SSH + psql) |
| `./deploy.sh` | Build and deploy to production server |
| `apply_migrations_remote.sh` | Apply SQL migrations on remote DB |
| `add_gasoline_diesel_categories_local.sh` | Add GASOLINE/DIESEL categories locally |

---

## Deployment

1. **Build**:
   ```bash
   ./gradlew bootJar
   ```

2. **Deploy** (uses `deploy.sh`):
   - Target: server and paths in `deploy.sh` (e.g. `SERVER_HOST`, `APP_NAME`, `DB_*`).
   - Script copies JAR and restarts the app; ensure Java 17 and PostgreSQL are set up on the server.

3. **Database**: Run migrations on the server (e.g. `apply_migrations_remote.sh` or manual `psql` with `all_migrations_consolidated.sql`).

4. **Environment**: Set production env vars on the server (JWT, SMS, MoPay, BizaoPayment, etc.) or adjust `application-prod.properties`.

---

## Troubleshooting

### App won’t start

- Check Java 17: `java -version`
- Check DB: PostgreSQL running, `pocketmoney_db` exists, credentials in active profile match.
- Check port: nothing else using `8383` (or `SERVER_PORT`).

### Database errors

- Ensure migrations are applied and payment categories exist (EFASHE, GASOLINE, DIESEL, etc.).
- For “relation does not exist”, run `all_migrations_consolidated.sql` or the relevant migration scripts.

### Auth / 401

- Use a valid JWT from `/api/public/users/login` or `/api/public/receivers/login`.
- Send header: `Authorization: Bearer <token>`.

### Check a transaction on remote server

```bash
./check_transaction.sh POCHCST17699627989216095
```

Requires SSH access to the host and DB credentials used in the script.

### Logs

- Dev: logging level in `application-dev.properties` (e.g. `logging.level.com.pocketmoney=DEBUG`).
- Prod: adjust in `application-prod.properties` or via env.

---

## Summary for New Joiners

1. Install **Java 17** and **PostgreSQL**.
2. Create DB `pocketmoney_db` and run migrations.
3. Run: `./gradlew bootRun --args='--spring.profiles.active=dev'`.
4. Use **Postman** or similar: login via `/api/public/users/login` or `/api/public/receivers/login`, then call APIs with the returned JWT in `Authorization: Bearer <token>`.
5. Read `application-dev.properties` and `application.properties` for config; use scripts in the repo for DB and deployment.

For more detail on EFASHE flows, see `EFASHE_COMPLETE_FLOW.md` and `EFASHE_API_REQUEST_BODIES.md` (if present in the repo).
