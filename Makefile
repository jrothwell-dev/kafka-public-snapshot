# Load environment variables from .env file
ifneq (,$(wildcard ./.env))
    include .env
    export
endif

export DOCKER_BUILDKIT=1

# Service names
SERVICES = safetyculture-poller wwcc-transformer compliance-notification-router notification-service manager-digest-service
COMPOSE_FILE = docker-compose.yml
SERVICES_FILE = docker-compose.services.yml
KAFKA_CONTAINER = kafka
KAFKA_BOOTSTRAP = localhost:9092

# Topic definitions (name:partitions:replication)
TOPICS = \
	reference.wwcc.required:1:1 \
	reference.compliance.rules:1:1 \
	raw.safetyculture.users:1:1 \
	raw.safetyculture.credentials:3:1 \
	processed.wwcc.status:3:1 \
	events.compliance.issues:3:1 \
	events.notifications.sent:3:1 \
	commands.notifications:3:1

.PHONY: help \
	up down reset clean health \
	services services-build services-up services-down services-restart services-logs \
	safetyculture-poller-logs safetyculture-poller-restart safetyculture-poller-rebuild \
	wwcc-transformer-logs wwcc-transformer-restart wwcc-transformer-rebuild \
	compliance-notification-router-logs compliance-notification-router-restart compliance-notification-router-rebuild \
	notification-service-logs notification-service-restart notification-service-rebuild \
	manager-digest-service-logs manager-digest-service-restart manager-digest-service-rebuild \
	topics clear-topics list-topics watch-% \
	seed \
	test-all test-integration validate test-reset test-seed test-verify test-full \
	status logs install-hooks

# ============================================================================
# Help
# ============================================================================

help:
	@echo "╔════════════════════════════════════════════════════════════════╗"
	@echo "║         Council Kafka Platform - Development Commands        ║"
	@echo "╚════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "📦 Infrastructure:"
	@echo "  make up              - Start infrastructure (Kafka, Redis, Postgres, etc.)"
	@echo "  make down            - Stop all infrastructure"
	@echo "  make reset           - Clean restart (down + clean + up)"
	@echo "  make clean           - Stop and remove all volumes"
	@echo "  make health          - Check health of infrastructure services"
	@echo ""
	@echo "🔧 Services - All:"
	@echo "  make services        - Build and start all microservices"
	@echo "  make services-build  - Build all microservices"
	@echo "  make services-up     - Start all microservices"
	@echo "  make services-down   - Stop all microservices"
	@echo "  make services-restart - Restart all microservices"
	@echo "  make services-logs   - View logs from all services"
	@echo ""
	@echo "🔨 Services - Individual (replace SERVICE with service name):"
	@echo "  make SERVICE-logs    - View logs for a service"
	@echo "  make SERVICE-restart - Restart a service"
	@echo "  make SERVICE-rebuild - Rebuild and restart a service"
	@echo ""
	@echo "📊 Kafka Topics:"
	@echo "  make topics          - Create all Kafka topics"
	@echo "  make clear-topics    - Delete and recreate all topics"
	@echo "  make list-topics     - List all Kafka topics"
	@echo "  make watch-TOPIC     - Watch messages on a topic (e.g., make watch-processed.wwcc.status)"
	@echo ""
	@echo "🌱 Data Seeding:"
	@echo "  make seed            - Seed required WWCC users from seed-data/required-users.json"
	@echo ""
	@echo "🧪 Testing:"
	@echo "  make test-all        - Run all unit tests for all services"
	@echo "  make test-integration - Run integration tests (requires Docker)"
	@echo "  make validate        - Validate pipeline health (message counts, service status, lag)"
	@echo "  make test-reset      - Complete pipeline reset (stop services, clear topics, clear Redis)"
	@echo "  make test-seed       - Seed consistent test data"
	@echo "  make test-verify     - Verify data flow and message counts"
	@echo "  make test-full       - Full test cycle (reset, start, seed, verify)"
	@echo ""
	@echo "📈 Monitoring:"
	@echo "  make status          - Show status of all containers"
	@echo "  make logs            - View logs from all services"
	@echo ""
	@echo "🔧 Git Hooks:"
	@echo "  make install-hooks   - Install git hooks (pre-push test validation)"
	@echo ""
	@echo "Available services: $(SERVICES)"

# ============================================================================
# Infrastructure Management
# ============================================================================

up:
	@echo "🚀 Starting infrastructure..."
	@docker-compose -f $(COMPOSE_FILE) up -d
	@echo "⏳ Waiting for services to be ready..."
	@sleep 10
	@$(MAKE) topics
	@echo "✅ Infrastructure ready!"

down:
	@echo "🛑 Stopping services..."
	@docker-compose -f $(SERVICES_FILE) down 2>/dev/null || true
	@docker-compose -f $(COMPOSE_FILE) down
	@echo "✅ All services stopped"

reset: down clean up

clean:
	@echo "🧹 Cleaning up volumes..."
	@docker-compose -f $(SERVICES_FILE) down -v 2>/dev/null || true
	@docker-compose -f $(COMPOSE_FILE) down -v
	@echo "✅ Cleanup complete"

health:
	@echo "🏥 Health Check:"
	@docker exec $(KAFKA_CONTAINER) kafka-broker-api-versions --bootstrap-server $(KAFKA_BOOTSTRAP) > /dev/null 2>&1 && echo "  Kafka: ✅" || echo "  Kafka: ❌"
	@docker exec postgres pg_isready > /dev/null 2>&1 && echo "  PostgreSQL: ✅" || echo "  PostgreSQL: ❌"
	@docker exec redis redis-cli ping > /dev/null 2>&1 && echo "  Redis: ✅" || echo "  Redis: ❌"

# ============================================================================
# Service Management (All Services)
# ============================================================================

services: services-build services-up

services-build:
	@echo "🔨 Building all services..."
	@[ -n "$$SAFETYCULTURE_API_TOKEN" ] || (echo "❌ ERROR: SAFETYCULTURE_API_TOKEN not set"; exit 1)
	@docker-compose -f $(SERVICES_FILE) build
	@echo "✅ All services built"

services-up:
	@echo "🚀 Starting all services..."
	@docker-compose -f $(SERVICES_FILE) up -d
	@echo "✅ All services started"

services-down:
	@echo "🛑 Stopping all services..."
	@docker-compose -f $(SERVICES_FILE) down
	@echo "✅ All services stopped"

services-restart: services-down services-up

services-logs:
	@docker-compose -f $(SERVICES_FILE) logs -f --tail=50

# ============================================================================
# Individual Service Commands
# ============================================================================

# safetyculture-poller
safetyculture-poller-logs:
	@docker-compose -f $(SERVICES_FILE) logs -f safetyculture-poller

safetyculture-poller-restart:
	@docker-compose -f $(SERVICES_FILE) restart safetyculture-poller

safetyculture-poller-rebuild:
	@echo "🔨 Rebuilding safetyculture-poller..."
	@docker-compose -f $(SERVICES_FILE) build safetyculture-poller
	@docker-compose -f $(SERVICES_FILE) up -d safetyculture-poller
	@echo "✅ safetyculture-poller rebuilt and restarted"

# wwcc-transformer
wwcc-transformer-logs:
	@docker-compose -f $(SERVICES_FILE) logs -f wwcc-transformer

wwcc-transformer-restart:
	@docker-compose -f $(SERVICES_FILE) restart wwcc-transformer

wwcc-transformer-rebuild:
	@echo "🔨 Rebuilding wwcc-transformer..."
	@docker-compose -f $(SERVICES_FILE) build wwcc-transformer
	@docker-compose -f $(SERVICES_FILE) up -d wwcc-transformer
	@echo "✅ wwcc-transformer rebuilt and restarted"

# compliance-notification-router
compliance-notification-router-logs:
	@docker-compose -f $(SERVICES_FILE) logs -f compliance-notification-router

compliance-notification-router-restart:
	@docker-compose -f $(SERVICES_FILE) restart compliance-notification-router

compliance-notification-router-rebuild:
	@echo "🔨 Rebuilding compliance-notification-router..."
	@docker-compose -f $(SERVICES_FILE) build compliance-notification-router
	@docker-compose -f $(SERVICES_FILE) up -d compliance-notification-router
	@echo "✅ compliance-notification-router rebuilt and restarted"

# notification-service
notification-service-logs:
	@docker-compose -f $(SERVICES_FILE) logs -f notification-service

notification-service-restart:
	@docker-compose -f $(SERVICES_FILE) restart notification-service

notification-service-rebuild:
	@echo "🔨 Rebuilding notification-service..."
	@docker-compose -f $(SERVICES_FILE) build notification-service
	@docker-compose -f $(SERVICES_FILE) up -d notification-service
	@echo "✅ notification-service rebuilt and restarted"

# manager-digest-service
manager-digest-service-logs:
	@docker-compose -f $(SERVICES_FILE) logs -f manager-digest-service

manager-digest-service-restart:
	@docker-compose -f $(SERVICES_FILE) restart manager-digest-service

manager-digest-service-rebuild:
	@echo "🔨 Rebuilding manager-digest-service..."
	@docker-compose -f $(SERVICES_FILE) build manager-digest-service
	@docker-compose -f $(SERVICES_FILE) up -d manager-digest-service
	@echo "✅ manager-digest-service rebuilt and restarted"

# ============================================================================
# Kafka Topics
# ============================================================================

topics:
	@echo "📊 Creating Kafka topics..."
	@docker exec $(KAFKA_CONTAINER) sh -c ' \
		for topic in $(TOPICS); do \
			IFS=":" read -r name partitions replication <<< "$$topic"; \
			kafka-topics --bootstrap-server $(KAFKA_BOOTSTRAP) --list 2>/dev/null | grep -q "^$$name$$" || \
			kafka-topics --create --topic $$name --partitions $$partitions --replication-factor $$replication \
				--bootstrap-server $(KAFKA_BOOTSTRAP) >/dev/null 2>&1; \
		done'
	@echo "✅ Topics ready"

clear-topics:
	@echo "🧹 Clearing all Kafka topics..."
	@docker exec $(KAFKA_CONTAINER) sh -c ' \
		for topic in $(TOPICS); do \
			IFS=":" read -r name partitions replication <<< "$$topic"; \
			kafka-topics --delete --topic $$name --bootstrap-server $(KAFKA_BOOTSTRAP) 2>/dev/null || true; \
		done'
	@sleep 5
	@echo "📊 Recreating topics..."
	@$(MAKE) topics
	@echo "✅ All topics cleared and recreated"

list-topics:
	@echo "📋 Kafka Topics:"
	@docker exec $(KAFKA_CONTAINER) kafka-topics --list --bootstrap-server $(KAFKA_BOOTSTRAP) | sort

watch-%:
	@echo "👀 Watching topic: $*"
	@docker exec -it $(KAFKA_CONTAINER) kafka-console-consumer \
		--topic $* \
		--from-beginning \
		--bootstrap-server $(KAFKA_BOOTSTRAP) \
		--property print.timestamp=true \
		--property print.key=true

# ============================================================================
# Data Seeding
# ============================================================================

seed:
	@echo "🌱 Seeding required users..."
	@if [ ! -f seed-data/required-users.json ]; then echo "❌ ERROR: seed-data/required-users.json not found"; exit 1; fi
	@jq -c . seed-data/required-users.json | docker exec -i $(KAFKA_CONTAINER) kafka-console-producer --topic reference.wwcc.required --bootstrap-server $(KAFKA_BOOTSTRAP)
	@echo "✅ Seeded 29 required users"

# ============================================================================
# Testing Infrastructure
# ============================================================================

test-integration:
	@echo "Running integration tests (requires Docker)..."
	@cd services/compliance-notification-router && sbt IntegrationTest/test
	@echo "✓ Integration tests passed"

validate:
	@./scripts/validate-pipeline.sh

test-all:
	@echo "Running all unit tests..."
	@cd services/safetyculture-poller && sbt test
	@cd services/wwcc-transformer && sbt test
	@cd services/compliance-notification-router && sbt test
	@cd services/notification-service && sbt test
	@echo "✓ All tests passed"

test-reset:
	@echo "🔄 Running pipeline reset..."
	@./scripts/reset-pipeline.sh

test-seed:
	@echo "🌱 Seeding test data..."
	@./scripts/seed-test-data.sh

test-verify:
	@echo "✅ Verifying pipeline..."
	@./scripts/verify-pipeline.sh

test-full: test-reset
	@echo "🚀 Starting services..."
	@$(MAKE) services-up
	@echo "⏳ Waiting 30 seconds for services to initialize..."
	@sleep 30
	@echo "🌱 Seeding test data..."
	@$(MAKE) test-seed
	@echo "⏳ Waiting 10 seconds for data to process..."
	@sleep 10
	@echo "✅ Verifying pipeline..."
	@$(MAKE) test-verify

# ============================================================================
# Git Hooks
# ============================================================================

install-hooks:
	@./scripts/install-hooks.sh

# ============================================================================
# Status & Logs
# ============================================================================

status:
	@echo "╔════════════════════════════════════════════════════════════════╗"
	@echo "║                    Container Status                            ║"
	@echo "╚════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "📦 Infrastructure:"
	@docker ps --format "  {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep -E "(kafka|redis|postgres|zookeeper|grafana|prometheus|traefik|loki)" || echo "  None running"
	@echo ""
	@echo "🔧 Services:"
	@docker ps --format "  {{.Names}}\t{{.Status}}" | grep -E "(safetyculture-poller|transformer|compliance|notification)" || echo "  None running"
	@echo ""
	@echo "🌐 Dashboards:"
	@echo "  Kafka UI:   http://localhost:8081"
	@echo "  Grafana:    http://localhost:3000"
	@echo "  Traefik:    http://localhost:8080"
	@echo "  Prometheus: http://localhost:9090"

logs:
	@docker-compose -f $(SERVICES_FILE) logs -f --tail=50
