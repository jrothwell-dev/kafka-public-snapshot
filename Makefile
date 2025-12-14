# Load environment variables from .env file
ifneq (,$(wildcard ./.env))
    include .env
    export
endif

export DOCKER_BUILDKIT=1

# Service names
SERVICES = sc-poller wwcc-transformer wwcc-compliance-monitor compliance-notification-router
COMPOSE_FILE = docker-compose.yml
SERVICES_FILE = docker-compose.services.yml
KAFKA_CONTAINER = kafka
KAFKA_BOOTSTRAP = localhost:9092

# Topic definitions (name:partitions:replication)
TOPICS = \
	reference.wwcc.required:1:1 \
	reference.compliance.rules:1:1 \
	raw.safetyculture.users:1:1 \
	raw.safetyculture.credentials:1:1 \
	processed.wwcc.status:3:1 \
	events.compliance.issues:3:1 \
	events.notifications.sent:3:1 \
	commands.notifications:5:1

.PHONY: help \
	up down reset clean \
	services services-build services-up services-down services-restart services-logs \
	sc-poller-build sc-poller-up sc-poller-down sc-poller-restart sc-poller-logs sc-poller-rebuild \
	wwcc-transformer-build wwcc-transformer-up wwcc-transformer-down wwcc-transformer-restart wwcc-transformer-logs wwcc-transformer-rebuild \
	wwcc-compliance-monitor-build wwcc-compliance-monitor-up wwcc-compliance-monitor-down wwcc-compliance-monitor-restart wwcc-compliance-monitor-logs wwcc-compliance-monitor-rebuild \
	compliance-notification-router-build compliance-notification-router-up compliance-notification-router-down compliance-notification-router-restart compliance-notification-router-logs compliance-notification-router-rebuild \
	topics clear-topics list-topics cleanup-old-topics \
	seed seed-rules seed-all rebuild-all \
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
	@echo "  make seed-rules      - Seed compliance rules"
	@echo "  make seed-all       - Seed all test data"
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

# sc-poller
sc-poller-build:
	@echo "🔨 Building sc-poller..."
	@docker-compose -f $(SERVICES_FILE) build sc-poller
	@echo "✅ sc-poller built"

sc-poller-up:
	@echo "🚀 Starting sc-poller..."
	@docker-compose -f $(SERVICES_FILE) up -d sc-poller
	@echo "✅ sc-poller started"

sc-poller-down:
	@docker-compose -f $(SERVICES_FILE) stop sc-poller

sc-poller-restart: sc-poller-down sc-poller-up

sc-poller-logs:
	@docker-compose -f $(SERVICES_FILE) logs -f sc-poller

sc-poller-rebuild: sc-poller-build sc-poller-up

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

# wwcc-compliance-monitor
wwcc-compliance-monitor-build:
	@echo "🔨 Building wwcc-compliance-monitor..."
	@docker-compose -f $(SERVICES_FILE) build wwcc-compliance-monitor
	@echo "✅ wwcc-compliance-monitor built"

wwcc-compliance-monitor-up:
	@echo "🚀 Starting wwcc-compliance-monitor..."
	@docker-compose -f $(SERVICES_FILE) up -d wwcc-compliance-monitor
	@echo "✅ wwcc-compliance-monitor started"

wwcc-compliance-monitor-down:
	@docker-compose -f $(SERVICES_FILE) stop wwcc-compliance-monitor

wwcc-compliance-monitor-restart: wwcc-compliance-monitor-down wwcc-compliance-monitor-up

wwcc-compliance-monitor-logs:
	@docker-compose -f $(SERVICES_FILE) logs -f wwcc-compliance-monitor

wwcc-compliance-monitor-rebuild: wwcc-compliance-monitor-build wwcc-compliance-monitor-up

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

seed-rules:
	@echo "🌱 Seeding compliance rules..."
	@if [ ! -f services/wwcc-compliance-monitor/compliance-rules.json ]; then \
		echo "❌ ERROR: compliance-rules.json not found"; \
		exit 1; \
	fi
	@cat services/wwcc-compliance-monitor/compliance-rules.json | jq -c '.' | docker exec -i $(KAFKA_CONTAINER) kafka-console-producer --topic reference.compliance.rules --bootstrap-server $(KAFKA_BOOTSTRAP)
	@echo "✅ Seeded compliance notification rules"

seed-all: seed seed-rules
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
	@docker ps --format "  {{.Names}}\t{{.Status}}" | grep -E "(sc-poller|transformer|compliance)" || echo "  None running"
	@echo ""
	@echo "🌐 Dashboards:"
	@echo "  Kafka UI:   http://localhost:8081"
	@echo "  Grafana:    http://localhost:3000"
	@echo "  Traefik:    http://localhost:8080"
	@echo "  Prometheus: http://localhost:9090"

logs:
	@docker-compose -f $(SERVICES_FILE) logs -f --tail=50
