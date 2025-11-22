.PHONY: help up down restart logs clean build run test email

# Default target
help:
	@echo "Available commands:"
	@echo ""
	@echo "Docker Operations:"
	@echo "  make up              - Start all containers"
	@echo "  make down            - Stop all containers"
	@echo "  make restart         - Restart all containers"
	@echo "  make logs            - Follow logs from all containers"
	@echo "  make clean           - Remove all containers and volumes (DESTRUCTIVE)"
	@echo ""
	@echo "Application:"
	@echo "  make build           - Build the Scala application"
	@echo "  make run             - Run the database poller"
	@echo "  make test            - Run tests"
	@echo "  make migrate         - Run database migrations"
	@echo ""
	@echo "Email Testing:"
	@echo "  make email TEMPLATE=test-email.txt              - Send specific email template"
	@echo "  make email TEMPLATE=test-email-html.html        - Send HTML template"
	@echo "  make email TEMPLATE=compliance-alert.txt        - Send compliance alert"
	@echo ""
	@echo "Kafka Operations:"
	@echo "  make kafka-topics    - List all Kafka topics"
	@echo "  make kafka-create    - Create default topics"
	@echo "  make kafka-consume   - Consume from user-events topic"
	@echo ""
	@echo "Database:"
	@echo "  make db-shell        - Open PostgreSQL shell"
	@echo ""
	@echo "Monitoring:"
	@echo "  make urls            - Show all service URLs"

# Docker operations
up:
	docker-compose up -d

down:
	docker-compose down

restart:
	docker-compose restart

logs:
	docker-compose logs -f

clean:
	docker-compose down -v
	cd app && sbt clean

# Application operations
build:
	cd app && sbt compile

run:
	cd app && sbt run

test:
	cd app && sbt test

migrate:
	cd app && sbt "runMain com.demo.MigrationRunner"

# Email operations
email:
ifndef TEMPLATE
	@echo "Error: TEMPLATE not specified"
	@echo "Usage: make email TEMPLATE=test-email.txt"
	@echo ""
	@echo "Available templates:"
	@ls app/src/main/resources/email-templates/ | sed 's/^/  /'
	@exit 1
endif
	cd app && sbt "runMain com.demo.SendEmail $(TEMPLATE)"

# Kafka operations
kafka-topics:
	docker exec -it kafka kafka-topics --list --bootstrap-server localhost:9092

kafka-create:
	docker exec -it kafka kafka-topics --create --topic user-events --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1 --if-not-exists
	docker exec -it kafka kafka-topics --create --topic database-changes --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1 --if-not-exists
	docker exec -it kafka kafka-topics --create --topic compliance-issues --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1 --if-not-exists
	docker exec -it kafka kafka-topics --create --topic notification-requests --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1 --if-not-exists
	docker exec -it kafka kafka-topics --create --topic notification-events --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1 --if-not-exists
	@echo ""
	@echo "Created topics:"
	@docker exec -it kafka kafka-topics --list --bootstrap-server localhost:9092

kafka-consume:
	docker exec -it kafka kafka-console-consumer --topic user-events --from-beginning --bootstrap-server localhost:9092

# Database operations
db-shell:
	docker exec -it postgres psql -U demo -d demodata

# Utility
urls:
	@echo "Service URLs (ensure ports are forwarded in VS Code):"
	@echo ""
	@echo "  Traefik Dashboard:  http://localhost:8081"
	@echo "  Kafka UI:           http://kafka-ui.localhost"
	@echo "  Prometheus:         http://prometheus.localhost"
	@echo "  Grafana:            http://grafana.localhost (admin/admin)"
	@echo ""