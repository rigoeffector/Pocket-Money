t = latest

## up: starts all containers in the background without forcing build
up:
	@echo "Starting Docker images..."
	docker compose up -d
	@echo "Docker images started"

## up_build: builds and starts all services
up_build:
	@echo "Building and starting Docker images..."
	docker compose up --build -d
	@echo "Docker images built and started!"

## Build single service and pass the service by using the argument s. Ex: make build s=ussd-service
build: $(s)
	@echo "Running USSD integration tests..."
	@$(MAKE) test_ussd
	@echo "Building and starting single service:" $(s)
	docker compose up --build -d $(s)
	@echo $(s) "built and started!"

## test_ussd: run USSD integration tests (uses real test database)
test_ussd:
	@echo "Running USSD tests..."
	cd ussd/services/ussd-service && GOWORK=off go test ./...

## down: destroy docker compose
down:
	@echo "Destroy docker compose..."
	docker compose down
	@echo "Done!"

## stop: stop docker compose
stop:
	@echo "Stopping docker compose..."
	docker compose stop
	@echo "Done!"

## start: start docker compose
start:
	@echo "Starting docker compose..."
	docker compose start
	@echo "Done!"

## deploy: build and push a single service image to Docker Hub
# Usage: make deploy s=pocketmoney-api t=latest
# Note: requires Docker Hub login and proper image naming

deploy: $(s)
	@echo "Building and deploying single service:" $(s)
	docker compose build $(s)
	@echo "Tag single service:" $(s)
	docker tag bralirwa-api-$(s) qonicsinc/lottery-$(s):$(t)
	@echo "Pushing image to docker hub" $(s)
	docker push qonicsinc/lottery-$(s):$(t)
	@if [ "$(t)" != "latest" ]; then \
		echo "Build also latest image"; \
		docker tag bralirwa-api-$(s) qonicsinc/lottery-$(s):latest; \
		echo "Push latest image"; \
		docker push qonicsinc/lottery-"$(s)":latest; \
	fi
	@echo $(s) "image pushed"

## recreate_postgres_test: Delete and recreate Docker container for PostgreSQL test
recreate_postgres_test:
	@echo "Stopping and removing existing PostgreSQL container..."
	docker stop postgres-test || true
	docker rm postgres-test || true
	@echo "Creating new PostgreSQL container for testing..."
	docker run --name postgres-test -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=123 -e POSTGRES_DB=leazi_db -p 5434:5432 -d qonicsinc/postgres-pgcron:17.5
	@echo "PostgreSQL test container recreated!"