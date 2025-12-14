# Cursor Context

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
- compliance-notification-router: ❌ TO CREATE - bridges events.compliance.issues → commands.notifications
- notification-service: ❌ TO CONTAINERIZE - exists in app/ but needs extraction to services/

## Key Decisions
- Topic naming: dot-separated (e.g., events.compliance.issues)
- Consumer group pattern: {service-name}-{topic-name}-v1
- All partitions standardized to 3 (except reference topics = 1)

## Next Steps
1. Create compliance-notification-router service
2. Containerize notification-service from app/
3. Test end-to-end pipeline
