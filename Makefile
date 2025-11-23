# Load environment variables from .env file
ifneq (,$(wildcard ./.env))
    include .env
    export
endif

export DOCKER_BUILDKIT=1

.PHONY: help up down clean status services logs seed topics reset seed-rules wwcc-compliance-monitor-build wwcc-compliance-monitor-up wwcc-compliance-monitor-logs

help:
	@echo "Council Kafka Platform Commands:"
	@echo "  make up         - Start infrastructure"
	@echo "  make down       - Stop everything"
	@echo "  make reset      - Clean restart"
	@echo "  make services   - Build/start microservices"
	@echo "  make status     - Show status"
	@echo "  make seed       - Seed test data"
	@echo "  make seed-rules - Seed compliance rules"

up:
	@docker-compose up -d
	@sleep 10
	@make topics

down:
	@docker-compose -f docker-compose.services.yml down 2>/dev/null || true
	@docker-compose down

reset: down clean up

clean:
	@docker-compose -f docker-compose.services.yml down -v 2>/dev/null || true
	@docker-compose down -v

# Create topics only if they don't exist (suppress warnings)
topics:
	@docker exec kafka sh -c ' \
		for topic in \
			"reference.wwcc.required:1:1" \
			"reference.compliance.rules:1:1" \
			"raw.safetyculture.users:1:1" \
			"raw.safetyculture.credentials:1:1" \
			"processed.wwcc.status:3:1" \
			"events.compliance.issues:3:1" \
			"events.notifications.sent:3:1" \
			"commands.notifications:5:1"; do \
			IFS=":" read -r name partitions replication <<< "$$topic"; \
			kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null | grep -q "^$$name$$" || \
			kafka-topics --create --topic $$name --partitions $$partitions --replication-factor $$replication \
				--bootstrap-server localhost:9092 >/dev/null 2>&1; \
		done'
	@echo "Topics ready"

clear-topics:
	@echo "Clearing all Kafka topics..."
	@docker exec kafka sh -c ' \
		for topic in \
			"reference.wwcc.required" \
			"reference.compliance.rules" \
			"raw.safetyculture.users" \
			"raw.safetyculture.credentials" \
			"processed.wwcc.status" \
			"events.compliance.issues" \
			"events.notifications.sent" \
			"commands.notifications"; do \
			kafka-topics --delete --topic $$topic --bootstrap-server localhost:9092 2>/dev/null || true; \
		done'
	@sleep 5
	@echo "Recreating topics..."
	@make topics
	@echo "✓ All topics cleared and recreated"

list-topics:
	@docker exec kafka kafka-topics --list --bootstrap-server localhost:9092 | sort

cleanup-old-topics:
	@docker exec kafka sh -c ' \
		for topic in \
			"raw-safetyculture-users" \
			"raw-safetyculture-credentials" \
			"processed-wwcc-status" \
			"events-compliance-issues" \
			"events-notifications-sent" \
			"commands-notifications" \
			"required-wwcc-users"; do \
			kafka-topics --delete --topic $$topic --bootstrap-server localhost:9092 2>/dev/null || true; \
		done'

services:
	@[ -n "${SAFETYCULTURE_API_TOKEN}" ] || (echo "ERROR: SAFETYCULTURE_API_TOKEN not set"; exit 1)
	@docker-compose -f docker-compose.services.yml build
	@docker-compose -f docker-compose.services.yml up -d

sc-poller-build:
	@docker-compose -f docker-compose.services.yml build sc-poller

sc-poller-up:
	@docker-compose -f docker-compose.services.yml up -d sc-poller

sc-poller-logs:
	@docker-compose -f docker-compose.services.yml logs -f sc-poller

wwcc-compliance-monitor-build:
	@docker-compose -f docker-compose.services.yml build wwcc-compliance-monitor

wwcc-compliance-monitor-up:
	@docker-compose -f docker-compose.services.yml up -d wwcc-compliance-monitor

wwcc-compliance-monitor-logs:
	@docker-compose -f docker-compose.services.yml logs -f wwcc-compliance-monitor

logs:
	@docker-compose -f docker-compose.services.yml logs -f --tail=50

seed:
	@echo '{"requiredUsers":[{"email":"jordanr@murrumbidgee.nsw.gov.au","firstName":"Jordan","lastName":"Rothwell","department":"IT Services","position":"Systems Administrator","requiresWwcc":true,"startDate":"2024-01-15"},{"email":"zackw@murrumbidgee.nsw.gov.au","firstName":"Zack","lastName":"Walsh","department":"Community Services","position":"Youth Worker","requiresWwcc":true,"startDate":"2024-03-01"},{"email":"sarahm@murrumbidgee.nsw.gov.au","firstName":"Sarah","lastName":"Mitchell","department":"Youth Programs","position":"Program Coordinator","requiresWwcc":true,"startDate":"2024-06-01"}],"timestamp":"'$$(date -Iseconds)'"}' | \
		docker exec -i kafka kafka-console-producer --topic reference.wwcc.required --bootstrap-server localhost:9092
	@echo "✓ Seeded required WWCC users list"

seed-rules:
	@cat services/wwcc-compliance-monitor/compliance-rules.json | \
		docker exec -i kafka kafka-console-producer --topic reference.compliance.rules --bootstrap-server localhost:9092
	@echo "✓ Seeded compliance notification rules"

status:
	@echo "Infrastructure:"
	@docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "(kafka|redis|postgres)" | head -5
	@echo "\nServices:"
	@docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "(sc-poller|transformer|compliance)" || echo "None running"
	@echo "\nDashboards:"
	@echo "Kafka UI:   http://localhost:8081"
	@echo "Grafana:    http://localhost:3000"
	@echo "Traefik:    http://localhost:8080"
	@echo "Prometheus: http://localhost:9090"

watch-%:
	@docker exec -it kafka kafka-console-consumer \
		--topic $* \
		--from-beginning \
		--bootstrap-server localhost:9092 \
		--property print.timestamp=true \
		--property print.key=true

health:
	@docker exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092 > /dev/null 2>&1 && echo "Kafka: ✅" || echo "Kafka: ❌"
	@docker exec postgres pg_isready > /dev/null 2>&1 && echo "PostgreSQL: ✅" || echo "PostgreSQL: ❌"
	@docker exec redis redis-cli ping > /dev/null 2>&1 && echo "Redis: ✅" || echo "Redis: ❌"