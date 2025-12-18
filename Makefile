# Load environment variables from .env file
ifneq (,$(wildcard ./.env))
    include .env
    export
endif

export DOCKER_BUILDKIT=1

# Service names
SERVICES = safetyculture-poller wwcc-transformer compliance-notification-router notification-service
COMPOSE_FILE = docker-compose.yml
SERVICES_FILE = docker-compose.services.yml
TEST_COMPOSE_FILE = docker-compose.test.yml
KAFKA_CONTAINER = kafka
KAFKA_BOOTSTRAP = localhost:9092
TEST_KAFKA_CONTAINER = test-kafka
TEST_KAFKA_BOOTSTRAP = localhost:9093

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
	up down reset clean \
	services services-build services-up services-down services-restart services-logs \
	safetyculture-poller-build safetyculture-poller-up safetyculture-poller-down safetyculture-poller-restart safetyculture-poller-logs safetyculture-poller-rebuild \
	wwcc-transformer-build wwcc-transformer-up wwcc-transformer-down wwcc-transformer-restart wwcc-transformer-logs wwcc-transformer-rebuild \
	compliance-notification-router-build compliance-notification-router-up compliance-notification-router-down compliance-notification-router-restart compliance-notification-router-logs compliance-notification-router-rebuild \
	notification-service-build notification-service-up notification-service-down notification-service-restart notification-service-logs notification-service-rebuild \
	topics clear-topics list-topics cleanup-old-topics \
	seed seed-all rebuild-all \
	test-all test-integration validate test-reset test-seed test-verify test-full test-watch \
	test-e2e test-e2e-up test-e2e-down test-e2e-logs \
	ci-test ci-build \
	status health logs watch \
	dev dev-build dev-up dev-down dev-restart

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
	@echo "🔧 Services:"
	@echo "  make services        - Build and start all microservices"
	@echo "  make services-build  - Build all microservices"
	@echo "  make services-up     - Start all microservices"
	@echo "  make services-down   - Stop all microservices"
	@echo "  make services-restart - Restart all microservices"
	@echo "  make services-logs   - View logs from all services"
	@echo ""
	@echo "🔨 Individual Service Commands (replace SERVICE with service name):"
	@echo "  make SERVICE-build   - Build a service"
	@echo "  make SERVICE-up      - Start a service"
	@echo "  make SERVICE-down    - Stop a service"
	@echo "  make SERVICE-restart - Restart a service"
	@echo "  make SERVICE-logs    - View logs for a service"
	@echo "  make SERVICE-rebuild - Rebuild and restart a service"
	@echo ""
	@echo "📊 Kafka Topics:"
	@echo "  make topics          - Create all Kafka topics"
	@echo "  make clear-topics   - Delete and recreate all topics"
	@echo "  make list-topics     - List all Kafka topics"
	@echo "  make watch-TOPIC    - Watch messages on a topic (e.g., make watch-processed.wwcc.status)"
	@echo ""
	@echo "🌱 Data Seeding:"
	@echo "  make seed            - Seed required WWCC users"
	@echo "  make seed-all       - Seed all test data"
	@echo ""
	@echo "🧪 Testing Infrastructure:"
	@echo "  make test-all        - Run all unit tests for all services"
	@echo "  make test-integration - Run integration tests (requires Docker)"
	@echo "  make validate        - Validate pipeline health (message counts, service status, lag)"
	@echo "  make test-reset      - Complete pipeline reset (stop services, clear topics, clear Redis)"
	@echo "  make test-seed       - Seed consistent test data"
	@echo "  make test-verify     - Verify data flow and message counts"
	@echo "  make test-full       - Full test cycle (reset, start, seed, verify)"
	@echo "  make test-watch      - Watch all topics side by side"
	@echo ""
	@echo "🧪 E2E Test Environment (Isolated):"
	@echo "  make test-e2e        - Run full E2E test in isolated environment"
	@echo "  make test-e2e-up     - Start isolated test environment"
	@echo "  make test-e2e-down   - Stop isolated test environment"
	@echo "  make test-e2e-logs   - View logs from test environment"
	@echo ""
	@echo "🔄 CI/CD:"
	@echo "  make ci-test         - Full CI test cycle (reset, seed, verify)"
	@echo "  make ci-build        - Build all service Docker images"
	@echo ""
	@echo "🔄 Rebuild & Setup:"
	@echo "  make rebuild-all    - Rebuild all services, restart, and seed data"
	@echo ""
	@echo "🚀 Development:"
	@echo "  make dev             - Full dev setup (infra + services + seed)"
	@echo "  make dev-build       - Build all services (for development)"
	@echo "  make dev-up          - Start everything for development"
	@echo "  make dev-down        - Stop everything"
	@echo "  make dev-restart     - Restart everything"
	@echo ""
	@echo "📈 Monitoring:"
	@echo "  make status         - Show status of all containers"
	@echo "  make logs           - View logs from all services"
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
safetyculture-poller-build:
	@echo "🔨 Building safetyculture-poller..."
	@docker-compose -f $(SERVICES_FILE) build safetyculture-poller
	@echo "✅ safetyculture-poller built"

safetyculture-poller-up:
	@echo "🚀 Starting safetyculture-poller..."
	@docker-compose -f $(SERVICES_FILE) up -d safetyculture-poller
	@echo "✅ safetyculture-poller started"

safetyculture-poller-down:
	@docker-compose -f $(SERVICES_FILE) stop safetyculture-poller

safetyculture-poller-restart: safetyculture-poller-down safetyculture-poller-up

safetyculture-poller-logs:
	@docker-compose -f $(SERVICES_FILE) logs -f safetyculture-poller

safetyculture-poller-rebuild: safetyculture-poller-build safetyculture-poller-up

# wwcc-transformer
wwcc-transformer-build:
	@echo "🔨 Building wwcc-transformer..."
	@docker-compose -f $(SERVICES_FILE) build wwcc-transformer
	@echo "✅ wwcc-transformer built"

wwcc-transformer-up:
	@echo "🚀 Starting wwcc-transformer..."
	@docker-compose -f $(SERVICES_FILE) up -d wwcc-transformer
	@echo "✅ wwcc-transformer started"

wwcc-transformer-down:
	@docker-compose -f $(SERVICES_FILE) stop wwcc-transformer

wwcc-transformer-restart: wwcc-transformer-down wwcc-transformer-up

wwcc-transformer-logs:
	@docker-compose -f $(SERVICES_FILE) logs -f wwcc-transformer

wwcc-transformer-rebuild: wwcc-transformer-build wwcc-transformer-up

# compliance-notification-router
compliance-notification-router-build:
	@echo "🔨 Building compliance-notification-router..."
	@docker-compose -f $(SERVICES_FILE) build compliance-notification-router
	@echo "✅ compliance-notification-router built"

compliance-notification-router-up:
	@echo "🚀 Starting compliance-notification-router..."
	@docker-compose -f $(SERVICES_FILE) up -d compliance-notification-router
	@echo "✅ compliance-notification-router started"

compliance-notification-router-down:
	@docker-compose -f $(SERVICES_FILE) stop compliance-notification-router

compliance-notification-router-restart: compliance-notification-router-down compliance-notification-router-up

compliance-notification-router-logs:
	@docker-compose -f $(SERVICES_FILE) logs -f compliance-notification-router

compliance-notification-router-rebuild: compliance-notification-router-build compliance-notification-router-up

# notification-service
notification-service-build:
	@echo "🔨 Building notification-service..."
	@docker-compose -f $(SERVICES_FILE) build notification-service
	@echo "✅ notification-service built"

notification-service-up:
	@echo "🚀 Starting notification-service..."
	@docker-compose -f $(SERVICES_FILE) up -d notification-service
	@echo "✅ notification-service started"

notification-service-down:
	@docker-compose -f $(SERVICES_FILE) stop notification-service

notification-service-restart: notification-service-down notification-service-up

notification-service-logs:
	@docker-compose -f $(SERVICES_FILE) logs -f notification-service

notification-service-rebuild: notification-service-build notification-service-up

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

cleanup-old-topics:
	@echo "🧹 Cleaning up old topic names..."
	@docker exec $(KAFKA_CONTAINER) sh -c ' \
		for topic in \
			raw-safetyculture-users \
			raw-safetyculture-credentials \
			processed-wwcc-status \
			events-compliance-issues \
			events-notifications-sent \
			commands-notifications \
			required-wwcc-users; do \
			kafka-topics --delete --topic $$topic --bootstrap-server $(KAFKA_BOOTSTRAP) 2>/dev/null || true; \
		done'
	@echo "✅ Old topics cleaned up"

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
	@echo "🌱 Seeding required WWCC users..."
	@echo '{"requiredUsers":[{"email":"jordanr@murrumbidgee.nsw.gov.au","firstName":"Jordan","lastName":"Rothwell","department":"IT Services","position":"Systems Administrator","requiresWwcc":true,"startDate":"2024-01-15"},{"email":"zackw@murrumbidgee.nsw.gov.au","firstName":"Zack","lastName":"Walsh","department":"Community Services","position":"Youth Worker","requiresWwcc":true,"startDate":"2024-03-01"},{"email":"sarahm@murrumbidgee.nsw.gov.au","firstName":"Sarah","lastName":"Mitchell","department":"Youth Programs","position":"Program Coordinator","requiresWwcc":true,"startDate":"2024-06-01"}],"timestamp":"'$$(date -Iseconds)'"}' | \
		docker exec -i $(KAFKA_CONTAINER) kafka-console-producer --topic reference.wwcc.required --bootstrap-server $(KAFKA_BOOTSTRAP)
	@echo "✅ Seeded required WWCC users list"

seed-all: seed
	@echo "✅ All test data seeded"

# ============================================================================
# Complete Rebuild & Setup
# ============================================================================

rebuild-all: services-build services-up seed-all
	@echo ""
	@echo "🎉 All services rebuilt, restarted, and data seeded!"
	@echo "Run 'make status' to see running services"

# ============================================================================
# Development Workflow
# ============================================================================

dev: up services seed-all
	@echo ""
	@echo "🎉 Development environment ready!"
	@echo "Run 'make status' to see running services"
	@echo "Run 'make logs' to view service logs"

dev-build: services-build

dev-up: up services-up

dev-down: services-down down

dev-restart: services-restart

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

test-watch:
	@echo "👀 Watching all topics..."
	@echo "Press Ctrl+C to stop"
	@echo ""
	@echo "=== raw.safetyculture.credentials ==="
	@timeout 5 docker exec -it $(KAFKA_CONTAINER) kafka-console-consumer \
		--topic raw.safetyculture.credentials \
		--from-beginning \
		--bootstrap-server $(KAFKA_BOOTSTRAP) \
		--property print.timestamp=true \
		--property print.key=true \
		--max-messages 5 2>/dev/null || true
	@echo ""
	@echo "=== processed.wwcc.status ==="
	@timeout 5 docker exec -it $(KAFKA_CONTAINER) kafka-console-consumer \
		--topic processed.wwcc.status \
		--from-beginning \
		--bootstrap-server $(KAFKA_BOOTSTRAP) \
		--property print.timestamp=true \
		--property print.key=true \
		--max-messages 5 2>/dev/null || true
	@echo ""
	@echo "=== events.compliance.issues ==="
	@timeout 5 docker exec -it $(KAFKA_CONTAINER) kafka-console-consumer \
		--topic events.compliance.issues \
		--from-beginning \
		--bootstrap-server $(KAFKA_BOOTSTRAP) \
		--property print.timestamp=true \
		--property print.key=true \
		--max-messages 5 2>/dev/null || true
	@echo ""
	@echo "=== commands.notifications ==="
	@timeout 5 docker exec -it $(KAFKA_CONTAINER) kafka-console-consumer \
		--topic commands.notifications \
		--from-beginning \
		--bootstrap-server $(KAFKA_BOOTSTRAP) \
		--property print.timestamp=true \
		--property print.key=true \
		--max-messages 5 2>/dev/null || true

# ============================================================================
# CI/CD Targets
# ============================================================================

ci-test: test-reset
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

ci-build:
	@echo "🔨 Building all service Docker images..."
	@[ -n "$$SAFETYCULTURE_API_TOKEN" ] || (echo "⚠️  WARNING: SAFETYCULTURE_API_TOKEN not set, using dummy token for build"; export SAFETYCULTURE_API_TOKEN=dummy-token-for-build)
	@$(MAKE) services-build
	@echo "✅ All service images built"

# ============================================================================
# E2E Test Environment (Isolated)
# ============================================================================

test-e2e:
	@echo "🧪 Running E2E test in isolated environment..."
	@./scripts/test-e2e.sh

test-e2e-up:
	@echo "🚀 Starting isolated test environment..."
	@docker-compose -f $(TEST_COMPOSE_FILE) up -d
	@echo "⏳ Waiting for services to be ready..."
	@sleep 15
	@echo "📊 Creating Kafka topics..."
	@docker exec $(TEST_KAFKA_CONTAINER) sh -c ' \
		for topic in $(TOPICS); do \
			IFS=":" read -r name partitions replication <<< "$$topic"; \
			kafka-topics --bootstrap-server $(TEST_KAFKA_BOOTSTRAP) --list 2>/dev/null | grep -q "^$$name$$" || \
			kafka-topics --create --topic $$name --partitions $$partitions --replication-factor $$replication \
				--bootstrap-server $(TEST_KAFKA_BOOTSTRAP) >/dev/null 2>&1; \
		done'
	@echo "✅ Test environment ready!"
	@echo ""
	@echo "Test environment access:"
	@echo "  • Kafka UI:   http://localhost:8082"
	@echo "  • Kafka:      localhost:9093"
	@echo "  • Redis:      localhost:6380"

test-e2e-down:
	@echo "🛑 Stopping isolated test environment..."
	@docker-compose -f $(TEST_COMPOSE_FILE) down
	@echo "✅ Test environment stopped"

test-e2e-logs:
	@echo "📋 Test environment logs:"
	@docker-compose -f $(TEST_COMPOSE_FILE) logs -f --tail=50

logs:
	@docker-compose -f $(SERVICES_FILE) logs -f --tail=50
