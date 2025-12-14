# Quick Reference: Architecture Fixes

## Critical Issues to Fix

### 1. Missing Service: Compliance Notification Router

**Create:** `services/compliance-notification-router/`

This service bridges the gap between compliance issues and notifications.

**Files to create:**
- `services/compliance-notification-router/src/main/scala/ComplianceNotificationRouterService.scala`
- `services/compliance-notification-router/build.sbt`
- `services/compliance-notification-router/Dockerfile`

**Functionality:**
- Consumes `events.compliance.issues`
- Transforms `ComplianceIssue` → `NotificationRequest`
- Publishes to `commands.notifications`
- Resolves recipients based on `notificationConfig` (employee, manager, compliance team)

### 2. Fix Topic Name Mismatch

**Problem:** Notification service expects `notification-requests` but Makefile defines `commands.notifications`

**Fix Options:**

**Option A (Recommended):** Update notification service to use dot-separated names:
- Change `notification-requests` → `commands.notifications`
- Change `notification-events` → `events.notifications.sent`

**Option B:** Update Makefile to use hyphen-separated names (not recommended - breaks consistency)

### 3. Containerize Notification Service

**Steps:**
1. Move notification service from `app/src/main/scala/com/demo/notification/` to `services/notification-service/`
2. Create Dockerfile (similar to other services)
3. Add to `docker-compose.services.yml`
4. Convert from PureConfig to environment variables (match other services)
5. Update topic names to match Makefile

### 4. Standardize Consumer Group IDs

**Current (Inconsistent):**
- `wwcc-transformer-required-v2`
- `wwcc-transformer-v5`
- `compliance-monitor-v2`

**Recommended Pattern:** `{service-name}-{topic-name}-v1`

**New Groups:**
- `wwcc-transformer-required-v1`
- `wwcc-transformer-credentials-v1`
- `compliance-monitor-status-v1`
- `notification-router-issues-v1` (new service)
- `notification-service-commands-v1`

### 5. Update Partition Counts

**Makefile Changes:**
```makefile
# Change from:
raw.safetyculture.credentials:1:1
commands.notifications:5:1

# To:
raw.safetyculture.credentials:3:1
commands.notifications:3:1
```

---

## Implementation Checklist

- [ ] Create `compliance-notification-router` service
- [ ] Fix topic names in notification service config
- [ ] Containerize notification service
- [ ] Update consumer group IDs in all services
- [ ] Update partition counts in Makefile
- [ ] Add new service to `docker-compose.services.yml`
- [ ] Update Makefile with new service commands
- [ ] Test end-to-end pipeline
- [ ] Update documentation

---

## Schema Consistency Notes

✅ **Current schema transformation is correct:**
- API format (snake_case) → Internal format (camelCase)
- Transformation happens in `wwcc-transformer`
- All downstream services use camelCase consistently

⚠️ **Consider:** Shared models library or schema registry for future schema evolution
