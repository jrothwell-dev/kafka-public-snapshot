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

### 3. No Testing Infrastructure
- No easy way to reset/seed/verify pipeline
- Have to manually check Kafka UI topic by topic
- Need automated test validation

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
