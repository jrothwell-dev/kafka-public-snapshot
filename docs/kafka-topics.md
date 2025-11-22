# Kafka Topics Structure

## Raw Data (Extractors write here)
- raw.safetyculture.users - Raw user data from SafetyCulture API
- raw.safetyculture.credentials - Raw WWCC credential data from SafetyCulture API

## Processed Data (Transformers write here)  
- processed.wwcc.status - Transformed WWCC status with calculated expiry

## Events (Things that happened)
- events.compliance.issues - Detected compliance violations
- events.notifications.sent - Confirmation of sent notifications

## Commands (Things to do)
- commands.notifications - Requests to send notifications
- commands.poll.safetyculture - Trigger to poll SafetyCulture API