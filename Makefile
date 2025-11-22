# Load environment variables from .env file
ifneq (,$(wildcard ./.env))
    include .env
    export
endif

export DOCKER_BUILDKIT=0

.PHONY: help up down clean status services logs seed topics

# Default target
help:
	@echo "Kafka Pipeline Commands:"
	@echo ""
	@echo "  make up          - Start infrastructure (Kafka, Redis, Postgres, monitoring)"
	@echo "  make down        - Stop everything"
	@echo "  make clean       - Remove everything including volumes (DESTRUCTIVE)"
	@echo ""
	@echo "  make services    - Build and start all services"
	@echo "  make status      - Show pipeline status"
	@echo "  make logs        - Show all logs (centralized)"
	@echo ""
	@echo "  make topics      - Create Kafka topics"
	@echo "  make seed        - Seed required users"

# Infrastructure
up:
	docker-compose up -d
	@echo "Waiting for Kafka to be ready..."
	@sleep 10
	make topics

down:
	docker-compose -f docker-compose.services.yml down 2>/dev/null || true
	docker-compose down

clean:
	docker-compose -f docker-compose.services.yml down -v 2>/dev/null || true
	docker-compose down -v

# Topics setup
topics:
	@docker exec kafka kafka-topics --create --topic raw-safetyculture-users --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1 --if-not-exists 2>/dev/null || true
	@docker exec kafka kafka-topics --create --topic raw-safetyculture-credentials --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1 --if-not-exists 2>/dev/null || true
	@docker exec kafka kafka-topics --create --topic processed-wwcc-status --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1 --if-not-exists 2>/dev/null || true
	@docker exec kafka kafka-topics --create --topic events-compliance-issues --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1 --if-not-exists 2>/dev/null || true
	@docker exec kafka kafka-topics --create --topic events-notifications-sent --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1 --if-not-exists 2>/dev/null || true
	@docker exec kafka kafka-topics --create --topic commands-notifications --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1 --if-not-exists 2>/dev/null || true
	@docker exec kafka kafka-topics --create --topic required-wwcc-users --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1 --if-not-exists 2>/dev/null || true
	@echo "✓ Topics ready"

# Services
services:
	@if [ -z "${SAFETYCULTURE_API_TOKEN}" ]; then \
		echo "ERROR: SAFETYCULTURE_API_TOKEN not set in .env"; \
		exit 1; \
	fi
	docker-compose -f docker-compose.services.yml build
	docker-compose -f docker-compose.services.yml up -d
	@echo "✓ Services started"

# Centralized logging
logs:
	docker-compose -f docker-compose.services.yml logs -f --tail=50

# Seed data
seed:
	@echo '{"email":"jordanr@murrumbidgee.nsw.gov.au","department":"IT Services","requires_wwcc":true}' | \
		docker exec -i kafka kafka-console-producer --topic required-wwcc-users --bootstrap-server localhost:9092
	@echo "✓ Seeded required users"

# Status dashboard
status:
	@clear
	@echo "╔════════════════════════════════════════════════════════════╗"
	@echo "║                  KAFKA PIPELINE STATUS                        ║"
	@echo "╚════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "Infrastructure:"
	@docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "(kafka|redis|postgres|traefik)" | head -10 || true
	@echo ""
	@echo "Services:"
	@docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "(sc-poller|wwcc-transformer|compliance)" || echo "  No services running yet"
	@echo ""
	@echo "Dashboards:"
	@echo "  • Kafka UI: http://kafka-ui.localhost"
	@echo "  • Grafana:  http://grafana.localhost (admin/admin)"
	@echo "    Query: {container=~\"sc-poller.*\"} for logs"
	@echo "  • Traefik:  http://localhost:8081"

# Development helpers (hidden from help)
watch-%:
	docker exec -it kafka kafka-console-consumer --topic $* --from-beginning --bootstrap-server localhost:9092