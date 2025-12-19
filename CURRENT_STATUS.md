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
- [x] Test visibility dashboard with Prometheus Pushgateway
  - Added Prometheus Pushgateway service to docker-compose.yml (port 9091)
  - Updated prometheus.yml to scrape Pushgateway metrics
  - Created scripts/run-tests-with-metrics.sh that:
    - Runs sbt test for each service (safetyculture-poller, wwcc-transformer, compliance-notification-router, notification-service, manager-digest-service)
    - Parses test output for pass/fail counts and duration
    - Pushes metrics to Pushgateway using curl (graceful fallback if Pushgateway unavailable)
    - Tracks: wwcc_tests_total{service,status}, wwcc_tests_duration_seconds{service}, wwcc_tests_last_run_timestamp{service}
  - Created Grafana dashboard at config/grafana/dashboards/test-results.json showing:
    - Total tests passed/failed
    - Test counts over time per service
    - Test duration trends
    - Per-service test status panels
    - Test status table with last run timestamps
  - Added make test-with-metrics target to run tests with metrics collection

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
- Phase 2: Configuration externalization - complete
  - Moved notification configuration from JSON resources to external YAML file
  - Added SnakeYAML library for YAML parsing
  - Created config/notification-settings.yaml with comprehensive settings
  - Updated ComplianceNotificationRouterService to load from external YAML
  - Added volume mount for config file in docker-compose
  - Configuration now editable without rebuilding containers
  - All tests passing (25 tests in compliance-notification-router)
- Phase 4: Frequency-based notification scheduling - complete
  - Implemented frequency rule evaluation based on days until expiry
  - Added evaluateCondition function to parse and evaluate frequency conditions
  - Added getNotificationIntervalSeconds to determine notification intervals
  - Updated dedup logic to use timestamps instead of simple existence checks
  - Added shouldSendNotification to check if enough time has passed since last notification
  - Updated main loop to use frequency-based scheduling instead of simple 24-hour dedup
  - Notifications now sent more frequently for urgent cases (expired/critical) and less frequently for early warnings
  - Added comprehensive tests for frequency rule evaluation (32 tests passing)
- [x] manager-digest-service - complete
  - New service for weekly digest emails to department managers
  - Consumes from processed.wwcc.status to track latest compliance state per user
  - Maintains in-memory map of latest compliance per userId
  - Checks every 30 seconds if current time matches scheduled digest time (with 5-min window)
  - Only sends once per day (tracks last sent date)
  - Aggregates issues by department, sends one email per department manager
  - Publishes to commands.notifications with issueType: "DIGEST"
  - Uses override recipient from config during development
  - Created manager-digest.html email template showing department summary with issue table
  - Added service to docker-compose.services.yml
  - Added Makefile targets following existing patterns
  - Unit tests: 33 tests passing (day/time parsing, isDigestTime logic, aggregateByDepartment, template data building)
  - Service scaffold complete with build.sbt, Dockerfile, and project structure
- [x] Email templates and notification routing updates - complete
  - Updated email templates to remove HR terminology and add council-appropriate contact details
  - Created new wwcc-individual-alert.html template with professional design, inclusive language for diverse recipients (councillors, daycare staff, PMCs)
  - Updated compliance-alert.txt plain text template with new content and contact details
  - Updated notification routing to exclude UNEXPECTED status from immediate notifications (will be handled in weekly digest only)
  - Updated shouldNotify function to explicitly match only EXPIRED, EXPIRING, MISSING, NOT_APPROVED statuses
  - Updated notification-settings.yaml to use wwcc-individual-alert.html template
  - Added test for UNEXPECTED status exclusion
  - All tests passing (33 tests in compliance-notification-router)

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

