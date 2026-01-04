#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

KAFKA_CONTAINER="kafka"
KAFKA_BOOTSTRAP="localhost:9092"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SEED_DATA_DIR="$PROJECT_ROOT/seed-data"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║              Seeding Test Data                                 ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Check if seed data files exist
if [ ! -d "$SEED_DATA_DIR" ]; then
  echo -e "${RED}❌ ERROR: seed-data directory not found at $SEED_DATA_DIR${NC}"
  exit 1
fi

# Step 1: Clear existing data
echo -e "${YELLOW}[1/3] Clearing existing data...${NC}"

# Clear topics by consuming all messages (timeout after 2 seconds)
TOPICS=(
  "reference.wwcc.required"
  "reference.compliance.rules"
)

for topic in "${TOPICS[@]}"; do
  timeout 2 docker exec "$KAFKA_CONTAINER" kafka-console-consumer \
    --topic "$topic" \
    --from-beginning \
    --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --max-messages 1000 \
    > /dev/null 2>&1 || true
done

echo -e "${GREEN}✓ Existing data cleared${NC}"
echo ""

# Step 2: Seed required users
echo -e "${YELLOW}[2/3] Seeding reference.wwcc.required with 29 required users...${NC}"

if [ ! -f "$SEED_DATA_DIR/required-users.json" ]; then
  echo -e "${RED}❌ ERROR: required-users.json not found${NC}"
  exit 1
fi

jq -c . "$SEED_DATA_DIR/required-users.json" | \
  docker exec -i "$KAFKA_CONTAINER" kafka-console-producer \
    --topic "reference.wwcc.required" \
    --bootstrap-server "$KAFKA_BOOTSTRAP"

echo -e "${GREEN}✓ Seeded 29 required users${NC}"
echo ""

# Step 3: Seed compliance rules
echo -e "${YELLOW}[3/3] Seeding reference.compliance.rules...${NC}"

if [ ! -f "$SEED_DATA_DIR/compliance-rules.json" ]; then
  echo -e "${RED}❌ ERROR: compliance-rules.json not found${NC}"
  exit 1
fi

jq -c . "$SEED_DATA_DIR/compliance-rules.json" | \
  docker exec -i "$KAFKA_CONTAINER" kafka-console-producer \
    --topic "reference.compliance.rules" \
    --bootstrap-server "$KAFKA_BOOTSTRAP"

echo -e "${GREEN}✓ Seeded compliance rules${NC}"
echo ""

# Summary
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║                    Seeding Complete!                         ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "Seeded data:"
echo "  • 29 required users (reference.wwcc.required)"
echo ""
echo "  • Compliance rules (reference.compliance.rules)"
echo ""
echo "Next step: Run 'make test-verify' to check the pipeline"
echo ""
