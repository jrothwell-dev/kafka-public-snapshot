# WWCC Compliance Monitoring Platform - Architecture Review

**Date:** 2024-12-19  
**Reviewer:** Architecture Analysis

---

## Executive Summary

This review identifies several critical issues in the Kafka streaming platform architecture, including topic naming inconsistencies, a missing service in the pipeline, consumer group ID inconsistencies, and schema naming conventions. The notification service exists but is not integrated into the containerized pipeline.

---

## 1. Topic Granularity & Naming

### Current Topics

| Topic | Partitions | Purpose | Status |
|-------|-----------|---------|--------|
| `reference.wwcc.required` | 1 | Reference data: users requiring WWCC | ✅ Good |
| `reference.compliance.rules` | 1 | Reference data: compliance rules | ✅ Good |
| `raw.safetyculture.credentials` | 1 | Raw credentials from SafetyCulture API | ⚠️ May bottleneck |
| `processed.wwcc.status` | 3 | Transformed compliance status | ✅ Good |
| `events.compliance.issues` | 3 | Detected compliance issues | ✅ Good |
| `events.notifications.sent` | 3 | Notification delivery events | ✅ Good |
| `commands.notifications` | 5 | Notification commands | ⚠️ Unused/Orphaned |

### Issues Found

#### ❌ **Critical: Topic Naming Inconsistency**

**Problem:** The notification service expects different topic names than what's defined in the Makefile.

- **Makefile defines:** `commands.notifications`, `events.notifications.sent`
- **Notification service expects:** `notification-requests`, `notification-events` (from `application.conf`)

**Impact:** The notification service cannot consume from the topics that are actually created.

**Recommendation:**
1. **Option A (Recommended):** Standardize on dot-separated naming convention:
   - Rename `commands.notifications` → keep as is (already correct)
   - Update notification service config to use `commands.notifications` and `events.notifications.sent`
   
2. **Option B:** Use hyphen-separated naming:
   - Rename all topics to use hyphens (e.g., `commands-notifications`)
   - Update Makefile accordingly

#### ⚠️ **Partition Count Concerns**

1. **`raw.safetyculture.credentials` (1 partition):**
   - **Issue:** Single partition may become a bottleneck if poll frequency increases or credential volume grows
   - **Recommendation:** Consider 3 partitions for better parallelism, especially if multiple transformer instances are needed

2. **`commands.notifications` (5 partitions):**
   - **Issue:** 5 partitions seems excessive for notification commands (typically lower volume than events)
   - **Recommendation:** Reduce to 3 partitions to match other event topics, unless you expect very high notification volume

3. **Reference topics (1 partition each):**
   - **Status:** ✅ Appropriate - reference data is low-volume and benefits from single partition for ordering

---

## 2. Data Flow & Pipeline Completeness

### Current Flow

```
sc-poller
  └─> raw.safetyculture.credentials

wwcc-transformer
  ├─> consumes: raw.safetyculture.credentials
  ├─> consumes: reference.wwcc.required
  └─> publishes: processed.wwcc.status

wwcc-compliance-monitor
  ├─> consumes: processed.wwcc.status
  ├─> consumes: reference.compliance.rules
  └─> publishes: events.compliance.issues

❌ MISSING SERVICE
  ├─> should consume: events.compliance.issues
  └─> should publish: commands.notifications

notification-service (exists but not containerized)
  ├─> expects: notification-requests (doesn't match commands.notifications)
  └─> publishes: notification-events (doesn't match events.notifications.sent)
```

### ❌ **Critical: Missing Service in Pipeline**

**Problem:** There is no service that bridges `events.compliance.issues` to `commands.notifications`. The compliance monitor publishes issues, but nothing converts them to notification commands.

**Impact:** Compliance issues are detected but never trigger notifications.

**Recommendation:** Create a new service `compliance-notification-router` that:
1. Consumes from `events.compliance.issues`
2. Transforms `ComplianceIssue` to `NotificationRequest` based on `notificationConfig`
3. Publishes to `commands.notifications`
4. Handles recipient resolution (employee, manager, compliance team)

**Service Structure:**
```scala
// Pseudo-code structure
class ComplianceNotificationRouter {
  def processIssue(issue: ComplianceIssue): Unit = {
    val recipients = determineRecipients(issue)
    val notificationRequest = NotificationRequest(
      notificationType = NotificationType.Email,
      template = issue.notificationConfig.template,
      recipients = recipients,
      data = buildTemplateData(issue),
      priority = Priority.fromString(issue.severity),
      requestedBy = "compliance-monitor"
    )
    producer.send("commands.notifications", notificationRequest)
  }
}
```

### ⚠️ **Notification Service Not Containerized**

**Problem:** The notification service exists in `app/src/main/scala/com/demo/notification/` but:
- Not included in `docker-compose.services.yml`
- Not built as a separate service
- Uses different configuration system (PureConfig vs environment variables)

**Recommendation:**
1. Extract notification service to `services/notification-service/`
2. Create Dockerfile similar to other services
3. Add to `docker-compose.services.yml`
4. Standardize configuration approach (environment variables like other services)
5. Update topic names to match Makefile

---

## 3. Consumer Group IDs

### Current Consumer Groups

| Service | Consumer Group ID | Topic | Status |
|---------|------------------|-------|--------|
| wwcc-transformer | `wwcc-transformer-required-v2` | `reference.wwcc.required` | ⚠️ Inconsistent |
| wwcc-transformer | `wwcc-transformer-v5` | `raw.safetyculture.credentials` | ⚠️ Inconsistent |
| wwcc-compliance-monitor | `compliance-monitor-v2` | `processed.wwcc.status` | ⚠️ Inconsistent |
| wwcc-compliance-monitor | `compliance-monitor-rules-temp-{UUID}` | `reference.compliance.rules` | ✅ Temporary (OK) |
| notification-service | `notification-service` | `notification-requests` | ⚠️ Doesn't match topic |

### Issues Found

#### ⚠️ **Inconsistent Versioning**

**Problem:** Consumer group IDs use different versioning schemes:
- `wwcc-transformer-required-v2` vs `wwcc-transformer-v5` (same service, different versions)
- `compliance-monitor-v2` (hardcoded version)

**Impact:** Makes it difficult to track which version of the service is running and can cause confusion during deployments.

**Recommendation:** Standardize consumer group naming:
```
{service-name}-{topic-name}-{version}
```

Examples:
- `wwcc-transformer-required-v1`
- `wwcc-transformer-credentials-v1`
- `compliance-monitor-status-v1`
- `notification-service-commands-v1`

Or simpler (if one consumer per service):
```
{service-name}-v1
```

#### ⚠️ **Missing Consumer Group for Rules**

**Problem:** The compliance monitor uses temporary consumer groups (`compliance-monitor-rules-temp-{UUID}`) to read rules. This is fine for one-time reads, but if you need to track which service instance last read rules, consider a persistent group.

**Recommendation:** Keep temporary groups for rules (they're reference data, read from beginning), but document this behavior.

---

## 4. Schema Consistency

### Field Naming Conventions

| Service | Input Format | Output Format | Status |
|---------|-------------|---------------|--------|
| sc-poller | API response (snake_case) | snake_case | ✅ Consistent with API |
| wwcc-transformer | snake_case | camelCase | ✅ Intentional transformation |
| wwcc-compliance-monitor | camelCase | camelCase | ✅ Consistent |
| notification-service | camelCase (expected) | camelCase | ✅ Consistent |

### Analysis

**Status:** ✅ **Schema transformation is intentional and appropriate**

The pipeline correctly transforms from API format (snake_case) to internal format (camelCase):
- `sc-poller` preserves API format: `first_name`, `last_name`, `expiry_status`
- `wwcc-transformer` converts to internal: `firstName`, `lastName`, `compliance_status`
- `wwcc-compliance-monitor` uses internal format throughout

**Recommendation:** Document this transformation pattern for future developers.

### Potential Issues

#### ⚠️ **Case Class Field Mismatch Risk**

**Problem:** If the transformer output schema changes, the compliance monitor must be updated simultaneously, or deserialization will fail.

**Recommendation:**
1. Consider using a shared schema registry (e.g., Confluent Schema Registry) for schema evolution
2. Or create a shared models library that both services depend on
3. Document schema contracts between services

---

## 5. Partition Strategy

### Partition Count Analysis

| Topic | Partitions | Expected Load | Consumer Groups | Assessment |
|-------|-----------|---------------|-----------------|------------|
| `reference.wwcc.required` | 1 | Very low | 1 | ✅ Appropriate |
| `reference.compliance.rules` | 1 | Very low | 1 | ✅ Appropriate |
| `raw.safetyculture.credentials` | 1 | Low-Medium | 1 | ⚠️ May bottleneck |
| `processed.wwcc.status` | 3 | Medium | 1 | ✅ Good for scaling |
| `events.compliance.issues` | 3 | Medium | 0 (none consuming) | ⚠️ Ready for consumer |
| `events.notifications.sent` | 3 | Low-Medium | 0 | ✅ Good for audit |
| `commands.notifications` | 5 | Low-Medium | 1 (when fixed) | ⚠️ Over-partitioned |

### Recommendations

1. **`raw.safetyculture.credentials`:**
   - **Current:** 1 partition
   - **Recommended:** 3 partitions
   - **Rationale:** Allows parallel processing if transformer instances scale

2. **`commands.notifications`:**
   - **Current:** 5 partitions
   - **Recommended:** 3 partitions
   - **Rationale:** Notification volume unlikely to require 5 partitions; 3 matches other event topics

3. **Other topics:** ✅ Appropriate as-is

---

## 6. Missing Pieces & Integration Gaps

### Critical Missing Components

#### ❌ **1. Compliance Issue → Notification Command Router**

**Status:** Missing service  
**Priority:** Critical  
**Description:** Service to convert `ComplianceIssue` events to `NotificationRequest` commands.

**Required Service:** `compliance-notification-router`
- Consumes: `events.compliance.issues`
- Publishes: `commands.notifications`
- Logic: Transform issues to notification requests based on `notificationConfig`

#### ❌ **2. Notification Service Containerization**

**Status:** Exists but not integrated  
**Priority:** High  
**Description:** Notification service needs to be:
- Extracted to `services/notification-service/`
- Containerized with Dockerfile
- Added to `docker-compose.services.yml`
- Updated to use correct topic names

#### ⚠️ **3. Topic Name Standardization**

**Status:** Inconsistent  
**Priority:** High  
**Description:** Resolve mismatch between Makefile topics and notification service config.

---

## 7. Recommended Architecture Changes

### Updated Data Flow

```
sc-poller
  └─> raw.safetyculture.credentials (3 partitions)

wwcc-transformer
  ├─> consumes: raw.safetyculture.credentials (group: wwcc-transformer-credentials-v1)
  ├─> consumes: reference.wwcc.required (group: wwcc-transformer-required-v1)
  └─> publishes: processed.wwcc.status (3 partitions)

wwcc-compliance-monitor
  ├─> consumes: processed.wwcc.status (group: compliance-monitor-status-v1)
  ├─> consumes: reference.compliance.rules (temp groups - OK)
  └─> publishes: events.compliance.issues (3 partitions)

compliance-notification-router [NEW]
  ├─> consumes: events.compliance.issues (group: notification-router-issues-v1)
  └─> publishes: commands.notifications (3 partitions)

notification-service [CONTAINERIZE]
  ├─> consumes: commands.notifications (group: notification-service-commands-v1)
  └─> publishes: events.notifications.sent (3 partitions)
```

### Topic Updates

| Current | Recommended | Reason |
|--------|-------------|--------|
| `raw.safetyculture.credentials` (1) | `raw.safetyculture.credentials` (3) | Better parallelism |
| `commands.notifications` (5) | `commands.notifications` (3) | Match other event topics |

### Consumer Group Standardization

**Pattern:** `{service-name}-{topic-name}-v{version}`

| Service | Topic | Current Group | Recommended Group |
|---------|-------|---------------|-------------------|
| wwcc-transformer | `reference.wwcc.required` | `wwcc-transformer-required-v2` | `wwcc-transformer-required-v1` |
| wwcc-transformer | `raw.safetyculture.credentials` | `wwcc-transformer-v5` | `wwcc-transformer-credentials-v1` |
| wwcc-compliance-monitor | `processed.wwcc.status` | `compliance-monitor-v2` | `compliance-monitor-status-v1` |
| compliance-notification-router | `events.compliance.issues` | (none) | `notification-router-issues-v1` |
| notification-service | `commands.notifications` | `notification-service` | `notification-service-commands-v1` |

---

## 8. Implementation Priority

### Phase 1: Critical Fixes (Immediate)
1. ✅ Create `compliance-notification-router` service
2. ✅ Fix topic name mismatch (standardize on dot-separated)
3. ✅ Containerize notification service

### Phase 2: Improvements (Short-term)
4. ✅ Standardize consumer group IDs
5. ✅ Update partition counts (raw.credentials → 3, commands.notifications → 3)
6. ✅ Update Makefile with new service

### Phase 3: Enhancements (Medium-term)
7. Consider schema registry for schema evolution
8. Add monitoring/alerting for pipeline health
9. Document schema contracts between services

---

## 9. Summary of Issues

### Critical Issues
1. ❌ **Missing service:** No bridge between `events.compliance.issues` and `commands.notifications`
2. ❌ **Topic name mismatch:** Notification service expects different topic names than Makefile
3. ❌ **Notification service not containerized:** Exists but not integrated into pipeline

### High Priority Issues
4. ⚠️ **Consumer group inconsistency:** Different versioning schemes across services
5. ⚠️ **Partition strategy:** Some topics may be under/over-partitioned

### Medium Priority Issues
6. ⚠️ **Schema evolution:** No mechanism for handling schema changes
7. ⚠️ **Documentation:** Missing architecture diagrams and schema contracts

---

## 10. Next Steps

1. **Review this document** with the team
2. **Prioritize fixes** based on business needs
3. **Create tickets** for each recommended change
4. **Update architecture diagram** after changes are implemented
5. **Document schema contracts** between services

---

## Appendix: Architecture Diagram (Current vs Recommended)

### Current Architecture (Incomplete)
```
┌─────────────┐
│ sc-poller   │──┐
└─────────────┘  │
                 ▼
      ┌──────────────────────────┐
      │ raw.safetyculture.       │
      │ credentials (1 partition) │
      └──────────────────────────┘
                 │
                 ▼
┌────────────────────────────┐
│ wwcc-transformer           │──┐
│ - consumes: raw.credentials│  │
│ - consumes: reference.     │  │
│   wwcc.required            │  │
└────────────────────────────┘  │
                 │               │
                 ▼               │
      ┌──────────────────────────┘
      │ processed.wwcc.status     │
      │ (3 partitions)             │
      └──────────────────────────┘
                 │
                 ▼
┌────────────────────────────┐
│ wwcc-compliance-monitor    │──┐
│ - consumes: processed.status│  │
│ - consumes: reference.rules │  │
└────────────────────────────┘  │
                 │               │
                 ▼               │
      ┌──────────────────────────┘
      │ events.compliance.issues  │
      │ (3 partitions)             │
      └──────────────────────────┘
                 │
                 ▼
            ❌ ORPHANED ❌
      (No consumer - issues never trigger notifications)

┌────────────────────────────┐
│ notification-service        │
│ (Not containerized)         │
│ - expects: notification-    │
│   requests (wrong topic)    │
└────────────────────────────┘
```

### Recommended Architecture (Complete)
```
┌─────────────┐
│ sc-poller   │──┐
└─────────────┘  │
                 ▼
      ┌──────────────────────────┐
      │ raw.safetyculture.       │
      │ credentials (3 partitions)│
      └──────────────────────────┘
                 │
                 ▼
┌────────────────────────────┐
│ wwcc-transformer            │──┐
│ - consumes: raw.credentials │  │
│ - consumes: reference.     │  │
│   wwcc.required            │  │
└────────────────────────────┘  │
                 │               │
                 ▼               │
      ┌──────────────────────────┘
      │ processed.wwcc.status     │
      │ (3 partitions)             │
      └──────────────────────────┘
                 │
                 ▼
┌────────────────────────────┐
│ wwcc-compliance-monitor    │──┐
│ - consumes: processed.status│  │
│ - consumes: reference.rules │  │
└────────────────────────────┘  │
                 │               │
                 ▼               │
      ┌──────────────────────────┘
      │ events.compliance.issues  │
      │ (3 partitions)             │
      └──────────────────────────┘
                 │
                 ▼
┌────────────────────────────┐
│ compliance-notification-   │──┐
│ router [NEW]                │  │
│ - consumes: events.issues  │  │
└────────────────────────────┘  │
                 │               │
                 ▼               │
      ┌──────────────────────────┘
      │ commands.notifications    │
      │ (3 partitions)             │
      └──────────────────────────┘
                 │
                 ▼
┌────────────────────────────┐
│ notification-service        │──┐
│ [CONTAINERIZED]            │  │
│ - consumes: commands.      │  │
│   notifications            │  │
└────────────────────────────┘  │
                 │               │
                 ▼               │
      ┌──────────────────────────┘
      │ events.notifications.sent│
      │ (3 partitions)             │
      └──────────────────────────┘
```

---

**End of Review**
