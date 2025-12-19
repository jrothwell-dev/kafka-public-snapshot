#!/bin/bash
# Script to run all tests and push metrics to Prometheus Pushgateway

set -e

PUSHGATEWAY_URL="${PUSHGATEWAY_URL:-http://localhost:9091}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICES=(
  "safetyculture-poller"
  "wwcc-transformer"
  "compliance-notification-router"
  "notification-service"
  "manager-digest-service"
)

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to check if Pushgateway is available
check_pushgateway() {
  if curl -s --max-time 2 "${PUSHGATEWAY_URL}/metrics" > /dev/null 2>&1; then
    return 0
  else
    return 1
  fi
}

# Function to push metrics to Pushgateway
push_metrics() {
  local service=$1
  local passed=$2
  local failed=$3
  local duration=$4
  local timestamp=$5
  
  if ! check_pushgateway; then
    echo -e "${YELLOW}⚠️  Pushgateway not available, skipping metrics push${NC}"
    return 0
  fi
  
  # Push metrics using Pushgateway API
  # Format: metric_name{label="value"} metric_value timestamp
  cat <<EOF | curl -s --data-binary @- "${PUSHGATEWAY_URL}/metrics/job/test_results/instance/${service}" || true
# TYPE wwcc_tests_total counter
wwcc_tests_total{service="${service}",status="passed"} ${passed}
wwcc_tests_total{service="${service}",status="failed"} ${failed}
# TYPE wwcc_tests_duration_seconds gauge
wwcc_tests_duration_seconds{service="${service}"} ${duration}
# TYPE wwcc_tests_last_run_timestamp gauge
wwcc_tests_last_run_timestamp{service="${service}"} ${timestamp}
EOF
}

# Function to parse sbt test output
parse_test_results() {
  local output_file=$1
  local passed=0
  local failed=0
  
  # Look for test result patterns in sbt/ScalaTest output
  # ScalaTest typically outputs: "Tests: succeeded X, failed Y, ..."
  # or: "Total number of tests run: X"
  
  # Try ScalaTest format: "Tests: succeeded X, failed Y"
  if grep -q "Tests: succeeded" "$output_file"; then
    succeeded_line=$(grep "Tests: succeeded" "$output_file" | head -1)
    passed=$(echo "$succeeded_line" | grep -oE "succeeded [0-9]+" | grep -oE "[0-9]+" || echo "0")
    failed=$(echo "$succeeded_line" | grep -oE "failed [0-9]+" | grep -oE "[0-9]+" || echo "0")
  fi
  
  # Try alternative format: "Total number of tests run: X"
  if [ "$passed" = "0" ] && [ "$failed" = "0" ]; then
    total_line=$(grep -i "Total number of tests run:" "$output_file" | head -1 || echo "")
    if [ -n "$total_line" ]; then
      total=$(echo "$total_line" | grep -oE "[0-9]+" | head -1 || echo "0")
      # Check for failed count
      failed_line=$(grep -i "failed [0-9]" "$output_file" | head -1 || echo "")
      if [ -n "$failed_line" ]; then
        failed=$(echo "$failed_line" | grep -oE "failed [0-9]+" | grep -oE "[0-9]+" | head -1 || echo "0")
      fi
      passed=$((total - failed))
    fi
  fi
  
  # Try generic patterns: "passed", "succeeded", "failed"
  if [ "$passed" = "0" ] && [ "$failed" = "0" ]; then
    passed=$(grep -iE "passed|succeeded" "$output_file" | grep -oE "[0-9]+" | head -1 || echo "0")
    failed=$(grep -iE "failed|errors" "$output_file" | grep -oE "[0-9]+" | head -1 || echo "0")
  fi
  
  # If still no results, check exit code
  if [ "$passed" = "0" ] && [ "$failed" = "0" ]; then
    # Check if there are any test files
    if grep -q "No tests to run\|No tests found\|No test sources found" "$output_file"; then
      passed=0
      failed=0
    fi
  fi
  
  echo "$passed $failed"
}

# Main execution
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║         Running Tests with Metrics Collection                 ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

cd "$ROOT_DIR"

# Check Pushgateway availability
if check_pushgateway; then
  echo -e "${GREEN}✓${NC} Pushgateway is available at ${PUSHGATEWAY_URL}"
else
  echo -e "${YELLOW}⚠️  Pushgateway not available - metrics will not be pushed${NC}"
  echo "   Tests will still run, but metrics won't be collected"
fi

echo ""

TOTAL_PASSED=0
TOTAL_FAILED=0
FAILED_SERVICES=()

# Run tests for each service
for service in "${SERVICES[@]}"; do
  service_dir="${ROOT_DIR}/services/${service}"
  
  if [ ! -d "$service_dir" ]; then
    echo -e "${YELLOW}⚠️  Skipping ${service} - directory not found${NC}"
    continue
  fi
  
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "Testing: ${service}"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  
  cd "$service_dir"
  
  # Create temp file for test output
  TEST_OUTPUT=$(mktemp)
  TEST_START=$(date +%s)
  
  # Run tests and capture output
  if sbt -batch test > "$TEST_OUTPUT" 2>&1; then
    TEST_EXIT_CODE=0
  else
    TEST_EXIT_CODE=$?
  fi
  
  TEST_END=$(date +%s)
  TEST_DURATION=$((TEST_END - TEST_START))
  
  # Parse results
  RESULTS=$(parse_test_results "$TEST_OUTPUT")
  PASSED=$(echo "$RESULTS" | cut -d' ' -f1)
  FAILED=$(echo "$RESULTS" | cut -d' ' -f2)
  
  # If parsing failed, use exit code
  if [ "$PASSED" = "0" ] && [ "$FAILED" = "0" ] && [ "$TEST_EXIT_CODE" -eq 0 ]; then
    # Try to count from output more aggressively
    if grep -q "test" "$TEST_OUTPUT" || grep -q "Test" "$TEST_OUTPUT"; then
      # Look for any number that might be test count
      TOTAL_TESTS=$(grep -oE "[0-9]+.*test" "$TEST_OUTPUT" | grep -oE "^[0-9]+" | head -1 || echo "0")
      if [ "$TOTAL_TESTS" != "0" ]; then
        PASSED=$TOTAL_TESTS
        FAILED=0
      fi
    fi
  fi
  
  # If still no results and tests failed, assume at least one failure
  if [ "$PASSED" = "0" ] && [ "$FAILED" = "0" ] && [ "$TEST_EXIT_CODE" -ne 0 ]; then
    FAILED=1
  fi
  
  TOTAL_PASSED=$((TOTAL_PASSED + PASSED))
  TOTAL_FAILED=$((TOTAL_FAILED + FAILED))
  
  # Display results
  if [ "$TEST_EXIT_CODE" -eq 0 ] && [ "$FAILED" -eq 0 ]; then
    echo -e "${GREEN}✓${NC} Tests passed: ${PASSED}, Failed: ${FAILED}, Duration: ${TEST_DURATION}s"
  else
    echo -e "${RED}✗${NC} Tests passed: ${PASSED}, Failed: ${FAILED}, Duration: ${TEST_DURATION}s"
    FAILED_SERVICES+=("${service}")
    # Show last few lines of output for debugging
    echo "Last 10 lines of output:"
    tail -10 "$TEST_OUTPUT" | sed 's/^/  /'
  fi
  
  # Push metrics
  TIMESTAMP=$(date +%s)
  push_metrics "$service" "$PASSED" "$FAILED" "$TEST_DURATION" "$TIMESTAMP"
  
  # Cleanup
  rm -f "$TEST_OUTPUT"
  
  echo ""
done

# Summary
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Summary"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Total Passed: ${TOTAL_PASSED}"
echo "Total Failed: ${TOTAL_FAILED}"

if [ ${#FAILED_SERVICES[@]} -gt 0 ]; then
  echo -e "${RED}Failed Services:${NC} ${FAILED_SERVICES[*]}"
  echo ""
  echo -e "${RED}❌ Some tests failed${NC}"
  exit 1
else
  echo ""
  echo -e "${GREEN}✅ All tests passed${NC}"
  exit 0
fi

