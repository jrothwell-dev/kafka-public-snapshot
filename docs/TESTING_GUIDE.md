# Testing Guide - SafetyCulture API Integration

This guide walks you through using the new SafetyCulture API seeding functionality to push test credentials and verify the pipeline.

## Prerequisites

1. **SafetyCulture API Token**
   - You need a SafetyCulture API token with "Platform management: Credentials" permission
   - Set it as an environment variable: `export SAFETYCULTURE_API_TOKEN=your_token_here`
   - Or add it to your `.env` file (if using docker-compose with env_file)

2. **Test Users in SafetyCulture**
   - The following users must exist in your SafetyCulture organization:
     - jordanr@murrumbidgee.nsw.gov.au
     - zackw@murrumbidgee.nsw.gov.au
     - michaelc@murrumbidgee.nsw.gov.au
     - emmaw@murrumbidgee.nsw.gov.au
   - If users don't exist, the script will skip them with a warning

3. **Infrastructure Running**
   - Kafka, Redis, and other infrastructure should be running
   - Run `make up` if not already started

## Step-by-Step Testing

### Option 1: Full Test with SafetyCulture API (Recommended)

This pushes real credentials to SafetyCulture, which sc-poller will then fetch.

```bash
# 1. Set your API token
export SAFETYCULTURE_API_TOKEN=your_token_here

# 2. Start infrastructure (if not already running)
make up

# 3. Start services (sc-poller will fetch from SafetyCulture)
make services-up

# 4. Push credentials to SafetyCulture API
make seed-safetyculture

# Expected output:
# - Finds WWCC credential type
# - Searches for each user by email
# - Creates/updates credentials with test scenarios
# - Shows success/failure for each user

# 5. Wait for sc-poller to fetch (polls every 5 minutes by default)
# Or trigger manually by restarting sc-poller:
make sc-poller-restart

# 6. Seed reference data (users and rules) to Kafka
make test-seed

# 7. Verify the pipeline
make test-verify
```

### Option 2: Local-Only Testing (No API Token)

If you don't have an API token or want to test without SafetyCulture:

```bash
# 1. Start infrastructure
make up

# 2. Start services
make services-up

# 3. Seed test data directly to Kafka (skips SafetyCulture API)
./scripts/seed-test-data.sh --local-only

# Or use make (which calls the script):
# The script automatically detects missing token and falls back

# 4. Verify the pipeline
make test-verify
```

### Option 3: Complete Test Cycle

Run the full automated test cycle:

```bash
# This does: reset → start services → seed → verify
make test-full
```

Note: `test-full` uses `test-seed which will try SafetyCulture API if token is set.

## Understanding the Test Scenarios

The test data includes 5 users with different compliance states:

### 1. Jordan Rothwell - EXPIRED
- **Email**: jordanr@murrumbidgee.nsw.gov.au
- **Department**: IT Services
- **Credential Status**: Expired (expiry date: 2024-11-01 - in the past)
- **Expected Behavior**: Should trigger compliance alerts

### 2. Zack Walsh - EXPIRING
- **Email**: zackw@murrumbidgee.nsw.gov.au
- **Department**: Community Services
- **Credential Status**: Expiring soon (expiry date: 2024-12-20 - within 30 days)
- **Expected Behavior**: Should trigger warning notifications

### 3. Sarah Mitchell - MISSING
- **Email**: sarahm@murrumbidgee.nsw.gov.au
- **Department**: Youth Programs
- **Credential Status**: No credential in SafetyCulture
- **Expected Behavior**: Should trigger compliance alerts for missing credential

### 4. Michael Chen - VALID
- **Email**: michaelc@murrumbidgee.nsw.gov.au
- **Department**: Recreation
- **Credential Status**: Valid (expiry date: 2026-12-31 - far future)
- **Expected Behavior**: Should show as compliant, no alerts

### 5. Emma Wilson - NOT_APPROVED
- **Email**: emmaw@murrumbidgee.nsw.gov.au
- **Department**: Education
- **Credential Status**: Exists but pending approval
- **Expected Behavior**: Should trigger compliance alerts for unapproved credential

## Verifying Results

### Check Kafka Topics

```bash
# Watch credentials being fetched by sc-poller
make watch-raw.safetyculture.credentials

# Watch transformed WWCC status
make watch-processed.wwcc.status

# Watch compliance issues
make watch-events.compliance.issues

# Watch notification commands
make watch-commands.notifications
```

### Use the Verification Script

```bash
make test-verify
```

This will:
- Count messages in each topic
- Show sample messages
- Verify expected counts match
- Report any issues

### Manual Verification

```bash
# List all topics
make list-topics

# Check specific topic message count
docker exec kafka kafka-console-consumer \
  --topic processed.wwcc.status \
  --from-beginning \
  --bootstrap-server localhost:9092 \
  --max-messages 10 \
  --property print.key=true \
  --property print.timestamp=true
```

## Troubleshooting

### Issue: "SAFETYCULTURE_API_TOKEN not set"

**Solution**: Export the token or add to `.env` file
```bash
export SAFETYCULTURE_API_TOKEN=your_token_here
```

### Issue: "User not found in SafetyCulture"

**Solution**: The user doesn't exist in your SafetyCulture organization. Either:
- Create the user in SafetyCulture first
- Or use `--local-only` flag to skip API and seed directly to Kafka

### Issue: "Failed to create/update credential"

**Possible causes**:
1. API token lacks "Platform management: Credentials" permission
   - **Fix**: Generate a new token with correct permissions
2. Wrong API endpoint structure
   - **Fix**: Check SafetyCulture API docs for latest endpoint format
3. User doesn't have permission to create credentials
   - **Fix**: Ensure service user has appropriate permissions

### Issue: sc-poller not picking up credentials

**Check**:
1. Is sc-poller running? `docker ps | grep sc-poller`
2. Check sc-poller logs: `make sc-poller-logs`
3. Wait for next poll cycle (default: 5 minutes)
4. Or restart sc-poller: `make sc-poller-restart`

### Issue: No messages in topics

**Check**:
1. Are services running? `make status`
2. Check service logs for errors
3. Verify topics exist: `make list-topics`
4. Check if data was seeded: `make test-verify`

## Testing Workflow Examples

### Quick Local Test
```bash
make up
make services-up
./scripts/seed-test-data.sh --local-only
make test-verify
```

### Full Integration Test with API
```bash
export SAFETYCULTURE_API_TOKEN=your_token
make up
make services-up
make seed-safetyculture
sleep 60  # Wait for sc-poller to fetch
make test-seed  # Seed reference data
make test-verify
```

### Reset and Start Fresh
```bash
make test-reset  # Clears everything
make services-up
make test-seed
make test-verify
```

## Next Steps

After verifying the pipeline works:

1. **Monitor the pipeline**: Use `make test-watch` to see messages flow
2. **Check compliance alerts**: Verify that expired/expiring credentials trigger alerts
3. **Test notifications**: Ensure notification commands are generated
4. **Review logs**: Check service logs for any errors or warnings

## Script Reference

### seed-safetyculture.sh
- **Purpose**: Push credentials to SafetyCulture API
- **Requires**: `SAFETYCULTURE_API_TOKEN`
- **What it does**:
  1. Finds WWCC credential type
  2. Searches for test users by email
  3. Creates/updates credentials with test scenarios
  4. Reports success/failure for each user

### seed-test-data.sh
- **Purpose**: Seed all test data to Kafka
- **Options**:
  - `--local-only`: Skip SafetyCulture API, seed directly to Kafka
- **What it does**:
  1. Calls `seed-safetyculture.sh` (if token set and not --local-only)
  2. Clears existing Kafka data
  3. Seeds required users
  4. Seeds compliance rules
  5. Seeds mock credentials (if --local-only or no token)

### verify-pipeline.sh
- **Purpose**: Verify data flow through pipeline
- **What it checks**:
  - Message counts in each topic
  - Sample messages
  - Expected vs actual counts
  - Reports any discrepancies
