# Current Status

## Completed

- [x] GitHub Actions removed (using local webhook deployment)
- [x] Git hygiene cleaned up (.cursor ignored)
- [x] notification-service containerized with tests, docker-compose, and Makefile integration
  - Unit tests: 20 tests passing
  - Docker compose configuration added
  - Makefile targets added (build, up, down, restart, logs, rebuild)
- [x] Test seeding simplified to always use mock data (removed SafetyCulture API creation attempts)
  - seed-test-data.sh now always seeds mock credentials directly to Kafka
  - safetyculture-poller fetches real credentials from SafetyCulture on its own
- [x] Fixed test data alignment - mock credentials now match required users correctly
  - Updated mock-credentials.json to use correct enum values (EXPIRY_STATUS_*, DOCUMENT_APPROVAL_STATUS_*)
  - Fixed JSON seeding to use compact format (jq -c) to prevent message truncation
  - All test scenarios now work: EXPIRED, EXPIRING, VALID, NOT_APPROVED, MISSING
- [x] Fixed race condition bug in wwcc-transformer (credentials processed before required users loaded)
  - Added requiredUsersLoaded flag to prevent processing credentials until required users list is loaded
  - Prevents UNEXPECTED status for all credentials when they arrive before required users topic is consumed
  - Added once-per-minute logging when waiting for required users list
- [x] Added git pre-push hook to enforce tests pass before pushing
  - Created pre-push-hook.sh that runs all unit tests before allowing push
  - Created install-hooks.sh installer script
  - Added make install-hooks target for easy installation
  - Prevents broken code from being pushed to repository
- [x] Fixed reset-pipeline.sh topic deletion timing
  - Replaced simple 3-second sleep with proper wait loop that checks if topics are actually deleted
  - Waits up to 30 seconds, checking every 2 seconds for remaining topics
  - Prevents "already exists" errors when recreating topics

## In Progress

- Phase 1: Flexible notification model
  - Step 1 complete (model updated)
    - Updated NotificationCommand to support multiple recipients (to/cc/bcc) and explicit subject
    - Updated both compliance-notification-router and notification-service
    - Updated sendEmail function to handle CC and BCC recipients
    - Updated all tests to use new model
    - All tests passing (24 tests in compliance-notification-router, 22 tests in notification-service)
  - Step 2 complete (HTML templates)
    - Added Mustache templating library to notification-service
    - Created HTML email templates in config/email-templates/
    - Implemented template rendering with buildTemplateData function
    - Added template volume mount in docker-compose.services.yml
    - All tests passing (29 tests in notification-service)

## Next Up

- Set up webhook receiver
- Test notification flow end-to-end

## Parked/Future

- E2E test environment (docker-compose.test.yml)
  - Fully isolated test environment with separate Kafka, Redis, and services
  - Test environment uses separate network: `council-kafka-platform_test-network`
  - Test containers prefixed with `test-` (test-kafka, test-redis, etc.)
  - Different external ports to avoid conflicts
  - Created `scripts/test-e2e.sh` for full E2E test execution
  - Added Makefile targets: `test-e2e`, `test-e2e-up`, `test-e2e-down`, `test-e2e-logs`

