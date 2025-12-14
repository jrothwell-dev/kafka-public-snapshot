#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

KAFKA_CONTAINER="kafka"
KAFKA_BOOTSTRAP="localhost:9092"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║              Pipeline Verification                            ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Function to get message count for a topic
get_message_count() {
  local topic=$1
  docker exec "$KAFKA_CONTAINER" kafka-run-class kafka.tools.GetOffsetShell \
    --broker-list "$KAFKA_BOOTSTRAP" \
    --topic "$topic" \
    --time -1 \
    2>/dev/null | \
    awk -F: '{sum += $3} END {print sum}' || echo "0"
}

# Function to get latest message from a topic
get_latest_message() {
  local topic=$1
  timeout 3 docker exec "$KAFKA_CONTAINER" kafka-console-consumer \
    --topic "$topic" \
    --from-beginning \
    --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --max-messages 1 \
    --timeout-ms 2000 \
    2>/dev/null | tail -n 1 || echo "(no messages)"
}

# Function to format JSON
format_json() {
  echo "$1" | jq '.' 2>/dev/null || echo "$1"
}

# Expected counts
EXPECTED_RAW_CREDENTIALS=4
EXPECTED_PROCESSED_STATUS=5
EXPECTED_COMPLIANCE_ISSUES=4  # Jordan (EXPIRED), Zack (EXPIRING), Sarah (MISSING), Emma (NOT_APPROVED)
EXPECTED_NOTIFICATIONS=0  # Will be populated by notification router

# Track failures
FAILURES=0

echo "=== Pipeline Verification ==="
echo ""

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
echo "=== Latest Messages ==="
echo ""

echo -e "${YELLOW}raw.safetyculture.credentials:${NC}"
RAW_MSG=$(get_latest_message "raw.safetyculture.credentials")
if [ "$RAW_MSG" != "(no messages)" ]; then
  format_json "$RAW_MSG"
else
  echo "$RAW_MSG"
fi
echo ""

echo -e "${YELLOW}processed.wwcc.status:${NC}"
PROCESSED_MSG=$(get_latest_message "processed.wwcc.status")
if [ "$PROCESSED_MSG" != "(no messages)" ]; then
  format_json "$PROCESSED_MSG"
else
  echo "$PROCESSED_MSG"
fi
echo ""

echo -e "${YELLOW}events.compliance.issues:${NC}"
ISSUES_MSG=$(get_latest_message "events.compliance.issues")
if [ "$ISSUES_MSG" != "(no messages)" ]; then
  format_json "$ISSUES_MSG"
else
  echo "$ISSUES_MSG"
fi
echo ""

echo -e "${YELLOW}commands.notifications:${NC}"
NOTIFICATIONS_MSG=$(get_latest_message "commands.notifications")
if [ "$NOTIFICATIONS_MSG" != "(no messages)" ]; then
  format_json "$NOTIFICATIONS_MSG"
else
  echo "$NOTIFICATIONS_MSG"
fi
echo ""

# Expected vs Actual summary
echo "=== Expected vs Actual ==="
echo ""
echo "Test Scenarios:"
echo "  1. Jordan Rothwell (IT Services) - should have EXPIRED credential"
echo "  2. Zack Walsh (Community Services) - should have EXPIRING credential (within 30 days)"
echo "  3. Sarah Mitchell (Youth Programs) - should have MISSING credential (no SafetyCulture data)"
echo "  4. Michael Chen (Recreation) - should have VALID credential"
echo "  5. Emma Wilson (Education) - should have NOT_APPROVED credential"
echo ""

# Final result
echo "╔════════════════════════════════════════════════════════════════╗"
if [ $FAILURES -eq 0 ]; then
  echo -e "║  ${GREEN}Status: All checks passed!${NC}                                    ║"
  echo "╚════════════════════════════════════════════════════════════════╝"
  exit 0
else
  echo -e "║  ${RED}Status: $FAILURES check(s) failed${NC}                                    ║"
  echo "╚════════════════════════════════════════════════════════════════╝"
  exit 1
fi
