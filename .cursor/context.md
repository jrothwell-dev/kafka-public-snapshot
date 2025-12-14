# Cursor Context

## Rules for AI Assistant

IMPORTANT: Follow these rules for EVERY task:

1. After completing any task, update this file (.cursor/context.md) with:
   - What was done
   - Current status of components
   - Any new issues discovered

2. After completing any task, update docs/ARCHITECTURE_FIXES.md with:
   - Mark completed items as DONE
   - Add any new issues discovered
   - Update implementation notes

3. After completing any task, commit all changes:
   - Stage all changes: git add -A
   - Commit with descriptive message: git commit -m "type: description"
   - Confirm clean git status

4. Before starting any task, read:
   - This file for current project state
   - docs/ARCHITECTURE_FIXES.md for pending issues
   - docs/ARCHITECTURE_REVIEW.md for detailed architecture decisions

5. If you discover new issues or bugs during work:
   - Document them immediately in the "Known Issues" section below
   - Do not ignore them

These rules ensure continuity between chat sessions.

---

## Project Overview
WWCC Compliance Monitoring Platform - Kafka streaming pipeline for monitoring Working With Children Check compliance.

## Architecture Documentation
For detailed architecture information, read these local files (not in git):
- docs/ARCHITECTURE_REVIEW.md - Full architecture review with issues and recommendations
- docs/ARCHITECTURE_FIXES.md - Implementation checklist and fixes

## Current Pipeline Status
- sc-poller: ✅ Done
- wwcc-transformer: ✅ Done  
- wwcc-compliance-monitor: ✅ Done
- compliance-notification-router: ✅ Done - bridges events.compliance.issues → commands.notifications
- notification-service: ❌ TO CONTAINERIZE - exists in app/ but needs extraction to services/

## Key Decisions
- Topic naming: dot-separated (e.g., events.compliance.issues)
- Consumer group pattern: {service-name}-{topic-name}-v1
- All partitions standardized to 3 (except reference topics = 1)

## Testing Infrastructure

### Test Scripts
- `scripts/reset-pipeline.sh` - Complete pipeline reset (stop services, clear topics, clear Redis)
- `scripts/seed-test-data.sh` - Seed consistent test data to Kafka topics
  - Supports `--local-only` flag to skip SafetyCulture API and seed directly to Kafka
  - Automatically calls `seed-safetyculture.sh` if `SAFETYCULTURE_API_TOKEN` is set
- `scripts/seed-safetyculture.sh` - Push test credentials to SafetyCulture API
  - Requires `SAFETYCULTURE_API_TOKEN` environment variable
  - Creates/updates WWCC credentials for test users
  - Searches for users by email and finds WWCC credential type automatically
- `scripts/verify-pipeline.sh` - Verify data flow and message counts across topics

### Make Targets
- `make test-reset` - Complete pipeline reset (stop services, clear topics, clear Redis)
- `make test-seed` - Seed consistent test data (calls `seed-test-data.sh`)
- `make test-verify` - Verify data flow and message counts (calls `verify-pipeline.sh`)
- `make test-full` - Full test cycle (reset, start services, seed, verify)
- `make test-watch` - Watch all topics side by side
- `make seed-safetyculture` - Push credentials to SafetyCulture API (requires `SAFETYCULTURE_API_TOKEN`)

### Test User Scenarios
The test data uses **real SafetyCulture users** with actual User IDs. See `seed-data/safetyculture-users.csv` for reference.

1. **Jordan Rothwell** (jordanr@murrumbidgee.nsw.gov.au)
   - User ID: `user_9f1df31ae0e241eca8402bec126a2cda`
   - Department: IT Services
   - Scenario: **EXPIRED** - Credential expired (past date: 2024-11-01)
   - Should trigger compliance alerts

2. **Zack Walsh** (zackw@murrumbidgee.nsw.gov.au)
   - User ID: `user_80ecbab5eb5f4fec848c0d0c29b10c43`
   - Department: Community Services
   - Scenario: **EXPIRING** - Credential expiring soon (within 30 days: 2024-12-20)
   - Should trigger warning notifications

3. **Emma Bryce** (emmab@murrumbidgee.nsw.gov.au)
   - User ID: `user_c015c9a618aa4d539d0c0a6c0408e498`
   - Department: Youth Programs
   - Scenario: **MISSING** - No credential in SafetyCulture (no credential seeded)
   - Should trigger compliance alerts

4. **Stephen Lockhart** (stephenl@murrumbidgee.nsw.gov.au)
   - User ID: `user_63e34a09823d424db6be9f92ba5b81d4`
   - Department: Recreation
   - Scenario: **VALID** - Valid credential (expires 2026-12-31)
   - Should show as compliant

5. **Steve Goodsall** (steveg@murrumbidgee.nsw.gov.au)
   - User ID: `user_c1f017bccd144000ac5324fec0613fe4`
   - Department: Education
   - Scenario: **NOT_APPROVED** - Credential exists but pending approval
   - Should trigger compliance alerts

### SafetyCulture API Integration
- `seed-safetyculture.sh` attempts to push test credentials directly to SafetyCulture via their API
- **Note**: SafetyCulture API may not support creating credentials programmatically
- If API creation fails, credentials must be created manually in SafetyCulture UI:
  1. Go to SafetyCulture web app → Credentials
  2. Create credentials for test users with appropriate expiry dates
  3. sc-poller will automatically fetch them on next poll
- Requires `SAFETYCULTURE_API_TOKEN` with "Platform management: Credentials" permission
- Automatically searches for users by email and finds "Working With Children Check" credential type
- Debug mode: `make seed-safetyculture-debug` shows full API requests/responses
- Falls back gracefully if users don't exist or API calls fail
- **Primary method**: Use `make test-seed` with `--local-only` flag to seed mock data directly to Kafka

## Known Issues

### 1. Data Duplication in processed.wwcc.status
- Same users appearing multiple times (e.g., Jordan Rothwell, Zack Walsh)
- Some records show MISSING status, others show actual credential data
- This suggests transformer is processing both "missing user" checks AND real credentials separately
- Need deduplication - probably using Redis to track processed users

### 2. Pipeline Flow Broken
- processed.wwcc.status has 5 messages
- events.compliance.issues has 0 messages (compliance monitor not producing)
- commands.notifications has 0 messages (router has nothing to consume)
- Need to debug why compliance-monitor isn't producing issues

### 3. Testing Infrastructure
- ✅ Created test scripts (reset, seed, verify)
- ✅ Added make targets for test workflow
- ✅ Integrated SafetyCulture API seeding
- ⚠️  Some test scenarios may need refinement based on actual API behavior

### 4. Redis Not Utilized
- Redis is running but services don't use it for deduplication
- Should track: which users have been processed, last seen credentials, etc.

### 5. No Observability
- Grafana dashboards not configured
- No way to see message flow rates, errors, consumer lag
- Prometheus scrapers exist but no useful dashboards

### 6. No CI/CD
- No automated tests
- No build pipeline
- No deployment validation

## Next Steps (Priority Order)
1. Fix data duplication with Redis deduplication
2. Debug compliance-monitor to understand why it's not producing
3. Create clean test infrastructure (reset, seed, verify commands)
4. Set up Grafana dashboards for visibility
5. Add CI/CD pipeline with GitHub Actions
6. Only THEN continue adding notification-service
