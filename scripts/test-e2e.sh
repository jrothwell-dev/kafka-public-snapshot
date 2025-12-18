#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

TEST_KAFKA_CONTAINER="test-kafka"
TEST_KAFKA_BOOTSTRAP="localhost:9093"
TEST_REDIS_CONTAINER="test-redis"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SEED_DATA_DIR="$PROJECT_ROOT/seed-data"
COMPOSE_FILE="$PROJECT_ROOT/docker-compose.test.yml"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║         E2E Test Environment - Isolated Test Stack           ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Check if production is running
if docker ps --format "{{.Names}}" | grep -q "^kafka$"; then
  echo -e "${YELLOW}⚠️  WARNING: Production Kafka is running${NC}"
  echo -e "${YELLOW}   Test environment uses isolated network and different ports${NC}"
  echo -e "${YELLOW}   Production: localhost:9092, Test: localhost:9093${NC}"
  echo ""
fi

# Step 1: Start test infrastructure
echo -e "${BLUE}[1/5] Starting test infrastructure...${NC}"
docker-compose -f "$COMPOSE_FILE" up -d test-zookeeper test-kafka test-redis test-kafka-ui
echo -e "${GREEN}✓ Test infrastructure started${NC}"
echo ""

# Step 2: Wait for services to be ready
echo -e "${BLUE}[2/5] Waiting for services to be ready...${NC}"
echo "  Waiting for Kafka..."
timeout 60 bash -c 'until docker exec test-kafka kafka-topics --bootstrap-server localhost:9092 --list > /dev/null 2>&1; do sleep 2; done' || {
  echo -e "${RED}❌ ERROR: Kafka failed to start${NC}"
  docker-compose -f "$COMPOSE_FILE" logs test-kafka
  exit 1
}

echo "  Waiting for Redis..."
timeout 30 bash -c 'until docker exec test-redis redis-cli ping > /dev/null 2>&1; do sleep 1; done' || {
  echo -e "${RED}❌ ERROR: Redis failed to start${NC}"
  docker-compose -f "$COMPOSE_FILE" logs test-redis
  exit 1
}

echo -e "${GREEN}✓ Services ready${NC}"
echo ""

# Step 3: Create Kafka topics
echo -e "${BLUE}[3/5] Creating Kafka topics...${NC}"
TOPICS=(
  "reference.wwcc.required:1:1"
  "reference.compliance.rules:1:1"
  "raw.safetyculture.users:1:1"
  "raw.safetyculture.credentials:3:1"
  "processed.wwcc.status:3:1"
  "events.compliance.issues:3:1"
  "events.notifications.sent:3:1"
  "commands.notifications:3:1"
)

for topic_spec in "${TOPICS[@]}"; do
  IFS=":" read -r name partitions replication <<< "$topic_spec"
  if docker exec "$TEST_KAFKA_CONTAINER" kafka-topics --bootstrap-server "$TEST_KAFKA_BOOTSTRAP" --list 2>/dev/null | grep -q "^${name}$"; then
    echo "  Topic '$name' already exists, skipping"
  else
    docker exec "$TEST_KAFKA_CONTAINER" kafka-topics --create \
      --topic "$name" \
      --partitions "$partitions" \
      --replication-factor "$replication" \
      --bootstrap-server "$TEST_KAFKA_BOOTSTRAP" > /dev/null 2>&1
    echo "  Created topic: $name"
  fi
done
echo -e "${GREEN}✓ Topics created${NC}"
echo ""

# Step 4: Start test services
echo -e "${BLUE}[4/5] Starting test services...${NC}"
docker-compose -f "$COMPOSE_FILE" up -d \
  test-safetyculture-poller \
  test-wwcc-transformer \
  test-compliance-notification-router \
  test-notification-service
echo -e "${GREEN}✓ Test services started${NC}"
echo ""

# Step 5: Seed test data
echo -e "${BLUE}[5/5] Seeding test data...${NC}"

if [ ! -d "$SEED_DATA_DIR" ]; then
  echo -e "${RED}❌ ERROR: seed-data directory not found at $SEED_DATA_DIR${NC}"
  exit 1
fi

# Seed required users
if [ -f "$SEED_DATA_DIR/required-users.json" ]; then
  echo "  Seeding reference.wwcc.required..."
  jq -c . "$SEED_DATA_DIR/required-users.json" | \
    docker exec -i "$TEST_KAFKA_CONTAINER" kafka-console-producer \
      --topic "reference.wwcc.required" \
      --bootstrap-server "$TEST_KAFKA_BOOTSTRAP" > /dev/null 2>&1
  echo "  ✓ Seeded required users"
fi

# Seed compliance rules
if [ -f "$SEED_DATA_DIR/compliance-rules.json" ]; then
  echo "  Seeding reference.compliance.rules..."
  jq -c . "$SEED_DATA_DIR/compliance-rules.json" | \
    docker exec -i "$TEST_KAFKA_CONTAINER" kafka-console-producer \
      --topic "reference.compliance.rules" \
      --bootstrap-server "$TEST_KAFKA_BOOTSTRAP" > /dev/null 2>&1
  echo "  ✓ Seeded compliance rules"
fi

# Seed mock credentials
if [ -f "$SEED_DATA_DIR/mock-credentials.json" ]; then
  echo "  Seeding raw.safetyculture.credentials..."
  jq -c '.credentials[]' "$SEED_DATA_DIR/mock-credentials.json" | while read -r credential; do
    subject_user_id=$(echo "$credential" | jq -r '.subject_user_id')
    message=$(echo "$credential" | jq -c --arg polledAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" '{credential: ., polledAt: $polledAt}')
    printf "%s|%s\n" "$subject_user_id" "$message" | \
      docker exec -i "$TEST_KAFKA_CONTAINER" kafka-console-producer \
        --topic "raw.safetyculture.credentials" \
        --bootstrap-server "$TEST_KAFKA_BOOTSTRAP" \
        --property "parse.key=true" \
        --property "key.separator=|" > /dev/null 2>&1
  done
  echo "  ✓ Seeded mock credentials"
fi

echo -e "${GREEN}✓ Test data seeded${NC}"
echo ""

# Wait for processing
echo -e "${YELLOW}⏳ Waiting 15 seconds for services to process data...${NC}"
sleep 15
echo ""

# Step 6: Verify pipeline
echo -e "${BLUE}[6/6] Verifying pipeline...${NC}"
echo ""

# Function to get message count for a topic
get_message_count() {
  local topic=$1
  docker exec "$TEST_KAFKA_CONTAINER" kafka-run-class kafka.tools.GetOffsetShell \
    --broker-list "$TEST_KAFKA_BOOTSTRAP" \
    --topic "$topic" \
    --time -1 \
    2>/dev/null | \
    awk -F: '{sum += $3} END {print sum}' || echo "0"
}

# Expected counts
EXPECTED_RAW_CREDENTIALS=4
EXPECTED_PROCESSED_STATUS=5
EXPECTED_COMPLIANCE_ISSUES=4

FAILURES=0

# Check raw.safetyculture.credentials
RAW_COUNT=$(get_message_count "raw.safetyculture.credentials")
if [ "$RAW_COUNT" -eq "$EXPECTED_RAW_CREDENTIALS" ]; then
  echo -e "${GREEN}[OK]${NC} raw.safetyculture.credentials: $RAW_COUNT messages (expected: $EXPECTED_RAW_CREDENTIALS)"
else
  echo -e "${RED}[FAIL]${NC} raw.safetyculture.credentials: $RAW_COUNT messages (expected: $EXPECTED_RAW_CREDENTIALS)"
  FAILURES=$((FAILURES + 1))
fi

# Check processed.wwcc.status
PROCESSED_COUNT=$(get_message_count "processed.wwcc.status")
if [ "$PROCESSED_COUNT" -eq "$EXPECTED_PROCESSED_STATUS" ]; then
  echo -e "${GREEN}[OK]${NC} processed.wwcc.status: $PROCESSED_COUNT messages (expected: $EXPECTED_PROCESSED_STATUS)"
else
  echo -e "${RED}[FAIL]${NC} processed.wwcc.status: $PROCESSED_COUNT messages (expected: $EXPECTED_PROCESSED_STATUS)"
  FAILURES=$((FAILURES + 1))
fi

# Check events.compliance.issues
ISSUES_COUNT=$(get_message_count "events.compliance.issues")
if [ "$ISSUES_COUNT" -eq "$EXPECTED_COMPLIANCE_ISSUES" ]; then
  echo -e "${GREEN}[OK]${NC} events.compliance.issues: $ISSUES_COUNT messages (expected: $EXPECTED_COMPLIANCE_ISSUES)"
else
  echo -e "${RED}[FAIL]${NC} events.compliance.issues: $ISSUES_COUNT messages (expected: $EXPECTED_COMPLIANCE_ISSUES)"
  FAILURES=$((FAILURES + 1))
fi

# Check commands.notifications
NOTIFICATIONS_COUNT=$(get_message_count "commands.notifications")
echo -e "${YELLOW}[INFO]${NC} commands.notifications: $NOTIFICATIONS_COUNT messages"

echo ""

# Final result
echo "╔════════════════════════════════════════════════════════════════╗"
if [ $FAILURES -eq 0 ]; then
  echo -e "║  ${GREEN}✅ E2E Test PASSED - All checks passed!${NC}                        ║"
  echo "╚════════════════════════════════════════════════════════════════╝"
  echo ""
  echo "Test environment is running:"
  echo "  • Kafka UI:   http://localhost:8082"
  echo "  • Kafka:      localhost:9093"
  echo "  • Redis:      localhost:6380"
  echo ""
  echo "To stop the test environment, run: make test-e2e-down"
  exit 0
else
  echo -e "║  ${RED}❌ E2E Test FAILED - $FAILURES check(s) failed${NC}                        ║"
  echo "╚════════════════════════════════════════════════════════════════╝"
  echo ""
  echo "To view logs, run: make test-e2e-logs"
  exit 1
fi

