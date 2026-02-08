# Copilot instructions

## Project overview
- This repo is a Go monorepo for a USSD service built with Fiber.
- Primary service: `services/ussd-service` (listens on port 9000).
- Shared code: `libs/shared-package`.
- Local dev stack: Docker Compose (Postgres, Redis, ussd-service).
- Kubernetes manifests are under `k8s/`.

## Key paths
- API routes: `services/ussd-service/routes/api.go`.
- Controllers: `services/ussd-service/controller/`.
- Models: `services/ussd-service/model/`.
- Config/bootstrap: `services/ussd-service/config/`.
- Templates: `services/ussd-service/templates/`.
- App entrypoint: `services/ussd-service/main.go`.
- Example config: `services/ussd-service/config.yml.example`.

## Runtime configuration
- Viper loads `config.yml` (see example) and `ussd_config.json`.
- Redis and Postgres connection details come from `config.yml`.
- Default listen address is `0.0.0.0:9000`.

## USSD workflow and config
- Workflow steps live in `services/ussd-service/ussd_config.json` and are loaded by Viper in `main.go`.
- Alternative configs exist for different campaign states:
	- `services/ussd-service/ussd_config.main.json` (full flow)
	- `services/ussd-service/ussd_config.closed.json` (campaign closed message)
- The loader normalizes steps into Viper keys: `steps.<id>` for lookup in controllers.
- Step schema (per JSON):
	- `id`, `content`, `inputs[]`, `allow_back`, `validation`, `is_end_session`.
	- `inputs[]` fields: `input`, `value`, `action`, `next_step`, `validation`.
	- Optional: `content_type: "dynamic"` with `content` ending in `:fn` for dynamic content.
- The flow engine lives in `services/ussd-service/controller/Home.go`:
	- `processUSSD()` reads `steps.<id>` and routes to the next step.
	- Actions are dispatched via `callUserFunc()`; register new action names in its map.
	- Input validation hooks are optional and should be wired in the validation map in `processUSSD()`.
- Translations are defined in `services/ussd-service/locales/ussd.en.toml` and `services/ussd-service/locales/ussd.sw.toml`.
	- When `use_translation_keys` is true, `content` values should be i18n keys.
	- Language choice is stored on the session/customer and used by the localizer.

## Local development
- Build and start services: `make up_build`.
- Bring stack up/down: `make up`, `make down`.
- USSD endpoint: `GET /ussd/api/v1/webhook`.
- Service status: `GET /ussd/api/v1/service-status`.

## Coding guidelines
- Keep changes scoped to relevant service folders; avoid cross-service edits unless required.
- Preserve Fiber middleware order and existing routing structure.
- Prefer shared utilities from `libs/shared-package` when available.
- Add new config keys to `config.yml.example` and use Viper to read them.
- Avoid breaking public endpoints in `routes/api.go`.
- When adding USSD steps, keep actions and validations in sync with `callUserFunc()` and `processUSSD()`.

## Testing
- Unit tests live alongside controllers (e.g., `services/ussd-service/controller/*_test.go`).
- Run service tests from `services/ussd-service` with `go test ./...`.
