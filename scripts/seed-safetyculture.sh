#!/bin/bash
# Don't exit on errors - we want to process all users even if some fail
set +e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Check for API token
if [ -z "$SAFETYCULTURE_API_TOKEN" ]; then
  echo -e "${RED}❌ ERROR: SAFETYCULTURE_API_TOKEN environment variable not set${NC}"
  echo "Set it with: export SAFETYCULTURE_API_TOKEN=your_token_here"
  exit 1
fi

API_BASE="https://api.safetyculture.io"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SEED_DATA_DIR="$PROJECT_ROOT/seed-data"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║         Seeding SafetyCulture via API                         ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Function to make API calls
api_call() {
  local method=$1
  local endpoint=$2
  local body=$3
  local description=$4
  
  if [ -z "$body" ]; then
    curl -s -w "\n%{http_code}" -X "$method" \
      "$API_BASE$endpoint" \
      -H "Authorization: Bearer $SAFETYCULTURE_API_TOKEN" \
      -H "Content-Type: application/json" \
      -H "Accept: application/json" \
      2>/dev/null
  else
    echo "$body" | curl -s -w "\n%{http_code}" -X "$method" \
      "$API_BASE$endpoint" \
      -H "Authorization: Bearer $SAFETYCULTURE_API_TOKEN" \
      -H "Content-Type: application/json" \
      -H "Accept: application/json" \
      -d @- \
      2>/dev/null
  fi
}

# Function to search for user by email
search_user() {
  local email=$1
  local response=$(api_call "POST" "/users/search" "{\"email\":[\"$email\"]}" "searching for user $email")
  local http_code=$(echo "$response" | tail -n1)
  local body=$(echo "$response" | sed '$d')
  
  if [ "$http_code" = "200" ]; then
    echo "$body" | jq -r '.users[0].id // empty' 2>/dev/null || echo ""
  else
    echo ""
  fi
}

# Function to list credential types and find WWCC
get_wwcc_credential_type() {
  local response=$(api_call "POST" "/credentials/v1/credential-types" \
    '{"document_category":"DOCUMENT_CATEGORY_LICENSES_AND_CREDENTIALS"}' \
    "listing credential types")
  local http_code=$(echo "$response" | tail -n1)
  local body=$(echo "$response" | sed '$d')
  
  if [ "$http_code" = "200" ]; then
    # Try to find WWCC credential type (case insensitive)
    echo "$body" | jq -r '.credential_types[] | select(.name | test("(?i)(wwcc|working.*children|children.*check)")) | .id' 2>/dev/null | head -n1
  else
    echo ""
  fi
}

# Function to create/update credential document version
create_credential() {
  local user_id=$1
  local credential_type_id=$2
  local first_name=$3
  local last_name=$4
  local expiry_year=$5
  local expiry_month=$6
  local expiry_day=$7
  local credential_number=$8
  local approval_status=$9
  
  # Calculate dates
  local today=$(date -u +%Y-%m-%d)
  local start_date=$(date -u -d "1 year ago" +%Y-%m-%d)
  local expiry_date=$(printf "%04d-%02d-%02d" "$expiry_year" "$expiry_month" "$expiry_day")
  
  # Build request body for creating/updating credential
  # Note: This uses the UpdateDocumentVersionForUser endpoint pattern
  # The actual endpoint may vary based on SafetyCulture API version
  local request_body=$(cat <<EOF
{
  "subject_user_id": "$user_id",
  "document_type_id": "$credential_type_id",
  "attributes": {
    "expiry_period_start_date": {
      "year": $(date -u -d "$start_date" +%Y),
      "month": $(date -u -d "$start_date" +%-m),
      "day": $(date -u -d "$start_date" +%-d)
    },
    "expiry_period_end_date": {
      "year": $expiry_year,
      "month": $expiry_month,
      "day": $expiry_day
    },
    "credential_number": "$credential_number"
  },
  "approval": {
    "status": "$approval_status",
    "reason": "Test credential for compliance monitoring"
  }
}
EOF
)
  
  # Try to update/create credential
  # Note: SafetyCulture API may require different endpoints for create vs update
  # This attempts the update endpoint which may create if it doesn't exist
  local response=$(api_call "POST" "/credentials/v1/document-versions" "$request_body" \
    "creating credential for $first_name $last_name")
  local http_code=$(echo "$response" | tail -n1)
  local body=$(echo "$response" | sed '$d')
  
  if [ "$http_code" = "200" ] || [ "$http_code" = "201" ]; then
    return 0
  else
    echo "$body" >&2
    return 1
  fi
}

# Step 1: Get WWCC credential type
echo -e "${YELLOW}[1/4] Finding WWCC credential type...${NC}"
WWCC_TYPE_ID=$(get_wwcc_credential_type)

if [ -z "$WWCC_TYPE_ID" ]; then
  echo -e "${YELLOW}⚠️  Could not find WWCC credential type automatically${NC}"
  echo -e "${BLUE}   You may need to specify it manually or create it in SafetyCulture first${NC}"
  echo -e "${BLUE}   Using placeholder: doc-type-wwcc${NC}"
  WWCC_TYPE_ID="doc-type-wwcc"
else
  echo -e "${GREEN}✓ Found WWCC credential type: $WWCC_TYPE_ID${NC}"
fi
echo ""

# Step 2: Load test users
echo -e "${YELLOW}[2/4] Loading test users...${NC}"
if [ ! -f "$SEED_DATA_DIR/required-users.json" ]; then
  echo -e "${RED}❌ ERROR: required-users.json not found${NC}"
  exit 1
fi

# Test user scenarios (using real SafetyCulture users)
declare -A USER_SCENARIOS
USER_SCENARIOS["jordanr@murrumbidgee.nsw.gov.au"]="expired:2024:11:1:WWCC-123456:approved"
USER_SCENARIOS["zackw@murrumbidgee.nsw.gov.au"]="expiring:2024:12:20:WWCC-234567:approved"
USER_SCENARIOS["stephenl@murrumbidgee.nsw.gov.au"]="valid:2026:12:31:WWCC-345678:approved"
USER_SCENARIOS["steveg@murrumbidgee.nsw.gov.au"]="pending:2025:12:31:WWCC-456789:pending"

echo -e "${GREEN}✓ Loaded 4 test user scenarios${NC}"
echo ""

# Step 3: Process each user
echo -e "${YELLOW}[3/4] Creating/updating credentials for test users...${NC}"
echo ""

SUCCESS_COUNT=0
FAIL_COUNT=0

# Process each user (continue even if one fails)
for email in "${!USER_SCENARIOS[@]}"; do
  set +e  # Don't exit on errors for individual user processing
  IFS=':' read -r scenario expiry_year expiry_month expiry_day credential_number approval_status <<< "${USER_SCENARIOS[$email]}"
  
  # Get user info from seed data
  user_info=$(jq -r ".requiredUsers[] | select(.email == \"$email\")" "$SEED_DATA_DIR/required-users.json")
  first_name=$(echo "$user_info" | jq -r '.firstName')
  last_name=$(echo "$user_info" | jq -r '.lastName')
  
  echo -e "${BLUE}Processing: $first_name $last_name ($email)${NC}"
  echo -e "  Scenario: $scenario"
  echo -e "  Expiry: $expiry_year-$expiry_month-$expiry_day"
  echo -e "  Status: $approval_status"
  
  # Search for user in SafetyCulture
  user_id=$(search_user "$email")
  
  if [ -z "$user_id" ]; then
    echo -e "  ${YELLOW}⚠️  User not found in SafetyCulture (email: $email)${NC}"
    echo -e "  ${YELLOW}   Skipping - user may need to be created in SafetyCulture first${NC}"
    ((FAIL_COUNT++))
    echo ""
    continue
  fi
  
  echo -e "  ${GREEN}✓ Found user ID: $user_id${NC}"
  
  # Create credential
  if create_credential "$user_id" "$WWCC_TYPE_ID" "$first_name" "$last_name" \
     "$expiry_year" "$expiry_month" "$expiry_day" "$credential_number" "$approval_status"; then
    echo -e "  ${GREEN}✓ Credential created/updated successfully${NC}"
    ((SUCCESS_COUNT++))
  else
    echo -e "  ${RED}❌ Failed to create/update credential${NC}"
    echo -e "  ${YELLOW}   Note: This may require specific API permissions or a different endpoint${NC}"
    ((FAIL_COUNT++))
  fi
  echo ""
  set +e  # Reset error handling after each user
done

# Step 4: Summary
set +e  # Don't exit on summary errors
echo -e "${YELLOW}[4/4] Summary${NC}"
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║                    Seeding Complete!                         ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "Results:"
echo "  • Successful: $SUCCESS_COUNT"
echo "  • Failed/Skipped: $FAIL_COUNT"
echo ""

if [ $SUCCESS_COUNT -gt 0 ]; then
  echo -e "${GREEN}✓ Credentials pushed to SafetyCulture${NC}"
  echo -e "${BLUE}  sc-poller will pick up these credentials on its next poll${NC}"
  echo ""
fi

if [ $FAIL_COUNT -gt 0 ]; then
  echo -e "${YELLOW}⚠️  Some credentials could not be created${NC}"
  echo -e "${YELLOW}   Common reasons:${NC}"
  echo -e "${YELLOW}   - Users don't exist in SafetyCulture${NC}"
  echo -e "${YELLOW}   - API token lacks 'Platform management: Credentials' permission${NC}"
  echo -e "${YELLOW}   - Different API endpoint required for credential creation${NC}"
  echo ""
fi

echo "Test user scenarios:"
echo "  • Jordan Rothwell: EXPIRED (past date)"
echo "  • Zack Walsh: EXPIRING (within 30 days)"
echo "  • Michael Chen: VALID (1 year from now)"
echo "  • Emma Wilson: PENDING (pending approval status)"
echo ""
