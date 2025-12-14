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

# Parse arguments
LOCAL_ONLY=false
if [[ "$*" == *"--local-only"* ]]; then
  LOCAL_ONLY=true
fi

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║              Seeding Test Data                                 ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Check if seed data files exist
if [ ! -d "$SEED_DATA_DIR" ]; then
  echo -e "${RED}❌ ERROR: seed-data directory not found at $SEED_DATA_DIR${NC}"
  exit 1
fi

# Step 0: Push to SafetyCulture API (if token available and not local-only)
if [ "$LOCAL_ONLY" = false ] && [ -n "$SAFETYCULTURE_API_TOKEN" ]; then
  echo -e "${YELLOW}[0/4] Pushing credentials to SafetyCulture API...${NC}"
  echo ""
  if "$SCRIPT_DIR/seed-safetyculture.sh"; then
    echo -e "${GREEN}✓ SafetyCulture API seeding complete${NC}"
    echo ""
    echo -e "${BLUE}Note: Credentials pushed to SafetyCulture. sc-poller will pick them up.${NC}"
    echo -e "${BLUE}      Continuing with Kafka seeding for reference data...${NC}"
    echo ""
  else
    echo -e "${YELLOW}⚠️  SafetyCulture API seeding had issues, falling back to direct Kafka seeding${NC}"
    echo ""
  fi
elif [ "$LOCAL_ONLY" = true ]; then
  echo -e "${BLUE}ℹ️  --local-only flag set, skipping SafetyCulture API${NC}"
  echo ""
elif [ -z "$SAFETYCULTURE_API_TOKEN" ]; then
  echo -e "${BLUE}ℹ️  SAFETYCULTURE_API_TOKEN not set, skipping API seeding${NC}"
  echo -e "${BLUE}   Use --local-only to explicitly skip API, or set token to use API${NC}"
  echo ""
fi

# Step 1: Clear existing data
echo -e "${YELLOW}[1/4] Clearing existing data...${NC}"

# Clear topics by consuming all messages (timeout after 2 seconds)
TOPICS=(
  "reference.wwcc.required"
  "reference.compliance.rules"
  "raw.safetyculture.credentials"
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
echo -e "${YELLOW}[2/4] Seeding reference.wwcc.required with 5 test users...${NC}"

if [ ! -f "$SEED_DATA_DIR/required-users.json" ]; then
  echo -e "${RED}❌ ERROR: required-users.json not found${NC}"
  exit 1
fi

cat "$SEED_DATA_DIR/required-users.json" | \
  docker exec -i "$KAFKA_CONTAINER" kafka-console-producer \
    --topic "reference.wwcc.required" \
    --bootstrap-server "$KAFKA_BOOTSTRAP"

echo -e "${GREEN}✓ Seeded 5 required users${NC}"
echo ""

# Step 3: Seed compliance rules
echo -e "${YELLOW}[3/4] Seeding reference.compliance.rules...${NC}"

if [ ! -f "$SEED_DATA_DIR/compliance-rules.json" ]; then
  echo -e "${RED}❌ ERROR: compliance-rules.json not found${NC}"
  exit 1
fi

cat "$SEED_DATA_DIR/compliance-rules.json" | \
  docker exec -i "$KAFKA_CONTAINER" kafka-console-producer \
    --topic "reference.compliance.rules" \
    --bootstrap-server "$KAFKA_BOOTSTRAP"

echo -e "${GREEN}✓ Seeded compliance rules${NC}"
echo ""

# Step 4: Seed mock credentials (4 users - not Sarah)
# Only seed directly to Kafka if we're in local-only mode or API token not available
# Otherwise, sc-poller will pick up the real data from SafetyCulture
if [ "$LOCAL_ONLY" = true ] || [ -z "$SAFETYCULTURE_API_TOKEN" ]; then
  echo -e "${YELLOW}[4/4] Seeding raw.safetyculture.credentials with mock data...${NC}"

if [ ! -f "$SEED_DATA_DIR/mock-credentials.json" ]; then
  echo -e "${RED}❌ ERROR: mock-credentials.json not found${NC}"
  exit 1
fi

# Read the JSON file and send each credential as a separate message
jq -c '.credentials[]' "$SEED_DATA_DIR/mock-credentials.json" | while read -r credential; do
  # Extract subject_user_id for the key
  subject_user_id=$(echo "$credential" | jq -r '.subject_user_id')
  
  # Create the message with credential and polledAt
  message=$(echo "$credential" | jq -c --arg polledAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" '{credential: ., polledAt: $polledAt}')
  
  # Send with key using printf to format key|value
  printf "%s|%s\n" "$subject_user_id" "$message" | \
    docker exec -i "$KAFKA_CONTAINER" kafka-console-producer \
      --topic "raw.safetyculture.credentials" \
      --bootstrap-server "$KAFKA_BOOTSTRAP" \
      --property "parse.key=true" \
      --property "key.separator=|"
done

  echo -e "${GREEN}✓ Seeded 4 mock credentials (Jordan, Zack, Michael, Emma - not Sarah)${NC}"
  echo ""
else
  echo -e "${YELLOW}[4/4] Skipping direct Kafka credential seeding...${NC}"
  echo -e "${BLUE}   Credentials were pushed to SafetyCulture API${NC}"
  echo -e "${BLUE}   sc-poller will pick them up on its next poll${NC}"
  echo ""
fi

# Summary
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║                    Seeding Complete!                         ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "Seeded data:"
echo "  • 5 required users (reference.wwcc.required)"
echo "    - Jordan Rothwell (IT Services) - should show EXPIRED"
echo "    - Zack Walsh (Community Services) - should show EXPIRING"
echo "    - Emma Bryce (Youth Programs) - should show MISSING"
echo "    - Stephen Lockhart (Recreation) - should show VALID"
echo "    - Steve Goodsall (Education) - should show NOT_APPROVED"
echo ""
echo "  • Compliance rules (reference.compliance.rules)"
echo ""

if [ "$LOCAL_ONLY" = true ] || [ -z "$SAFETYCULTURE_API_TOKEN" ]; then
  echo "  • 4 mock credentials (raw.safetyculture.credentials)"
  echo "    - Jordan, Zack, Stephen, Steve (not Emma - she's MISSING scenario)"
else
  echo "  • Credentials pushed to SafetyCulture API"
  echo "    - sc-poller will fetch them on next poll"
fi
echo ""
echo "Next step: Run 'make test-verify' to check the pipeline"
echo ""
