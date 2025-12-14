#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

KAFKA_CONTAINER="kafka"
KAFKA_BOOTSTRAP="localhost:9092"
REDIS_CONTAINER="redis"
SERVICES_FILE="docker-compose.services.yml"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║              Pipeline Reset - Complete Cleanup                ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Step 1: Stop all services
echo -e "${YELLOW}[1/4] Stopping all services...${NC}"
docker-compose -f "$SERVICES_FILE" down 2>/dev/null || true
echo -e "${GREEN}✓ Services stopped${NC}"
echo ""

# Step 2: Delete all topics
echo -e "${YELLOW}[2/4] Deleting all Kafka topics...${NC}"
TOPICS=(
  "reference.wwcc.required"
  "reference.compliance.rules"
  "raw.safetyculture.users"
  "raw.safetyculture.credentials"
  "processed.wwcc.status"
  "events.compliance.issues"
  "events.notifications.sent"
  "commands.notifications"
)

for topic in "${TOPICS[@]}"; do
  docker exec "$KAFKA_CONTAINER" kafka-topics \
    --delete \
    --topic "$topic" \
    --bootstrap-server "$KAFKA_BOOTSTRAP" \
    2>/dev/null || true
done

# Wait for topics to be fully deleted
sleep 3
echo -e "${GREEN}✓ Topics deleted${NC}"
echo ""

# Step 3: Recreate topics with correct partition counts
echo -e "${YELLOW}[3/4] Recreating topics with correct partition counts...${NC}"

# Reference topics: 1 partition
docker exec "$KAFKA_CONTAINER" kafka-topics \
  --create \
  --topic "reference.wwcc.required" \
  --partitions 1 \
  --replication-factor 1 \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  2>/dev/null || true

docker exec "$KAFKA_CONTAINER" kafka-topics \
  --create \
  --topic "reference.compliance.rules" \
  --partitions 1 \
  --replication-factor 1 \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  2>/dev/null || true

docker exec "$KAFKA_CONTAINER" kafka-topics \
  --create \
  --topic "raw.safetyculture.users" \
  --partitions 1 \
  --replication-factor 1 \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  2>/dev/null || true

# Raw credentials: 3 partitions (per architecture review)
docker exec "$KAFKA_CONTAINER" kafka-topics \
  --create \
  --topic "raw.safetyculture.credentials" \
  --partitions 3 \
  --replication-factor 1 \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  2>/dev/null || true

# Processed and event topics: 3 partitions
docker exec "$KAFKA_CONTAINER" kafka-topics \
  --create \
  --topic "processed.wwcc.status" \
  --partitions 3 \
  --replication-factor 1 \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  2>/dev/null || true

docker exec "$KAFKA_CONTAINER" kafka-topics \
  --create \
  --topic "events.compliance.issues" \
  --partitions 3 \
  --replication-factor 1 \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  2>/dev/null || true

docker exec "$KAFKA_CONTAINER" kafka-topics \
  --create \
  --topic "events.notifications.sent" \
  --partitions 3 \
  --replication-factor 1 \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  2>/dev/null || true

# Commands: 3 partitions (per architecture review, reduced from 5)
docker exec "$KAFKA_CONTAINER" kafka-topics \
  --create \
  --topic "commands.notifications" \
  --partitions 3 \
  --replication-factor 1 \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  2>/dev/null || true

echo -e "${GREEN}✓ Topics recreated${NC}"
echo ""

# Step 4: Clear Redis keys
echo -e "${YELLOW}[4/4] Clearing Redis keys...${NC}"
docker exec "$REDIS_CONTAINER" redis-cli FLUSHDB > /dev/null 2>&1 || true
echo -e "${GREEN}✓ Redis cleared${NC}"
echo ""

# Confirmation
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║                    Reset Complete!                             ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "Summary:"
echo "  • All services stopped"
echo "  • All topics deleted and recreated"
echo "  • Redis keys cleared"
echo ""
echo "Next steps:"
echo "  • Run 'make test-seed' to seed test data"
echo "  • Run 'make services-up' to start services"
echo "  • Run 'make test-verify' to verify pipeline"
echo ""
