#!/bin/bash
echo "=== Pipeline Validation ==="
echo ""

for topic in raw.safetyculture.credentials processed.wwcc.status commands.notifications; do
  # Get LOG-END-OFFSET from consumer groups (shows total messages ever in topic)
  # This works even if messages have been consumed
  count=$(docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
    --all-groups --describe 2>/dev/null | \
    grep "$topic" | awk '{sum += $4} END {if (sum > 0) print sum; else print 0}')
  
  # If no consumer groups found, try GetOffsetShell
  if [ "$count" = "0" ] || [ -z "$count" ]; then
    count=$(docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
      --broker-list localhost:9092 --topic $topic --time -1 2>/dev/null | \
      awk -F: '{sum += $3} END {if (sum > 0) print sum; else print 0}')
  fi
  
  if [ "$count" = "0" ] || [ -z "$count" ]; then
    echo "⚠️  $topic: 0 messages"
  else
    echo "✅ $topic: $count total messages"
  fi
done

echo ""
echo "=== Service Status ==="
docker ps --format "{{.Names}}: {{.Status}}" | grep -E "(poller|transformer|router|sc-poller|compliance)" | while read line; do
  if echo "$line" | grep -q "Up"; then
    echo "✅ $line"
  else
    echo "❌ $line"
  fi
done

echo ""
echo "=== Consumer Group Lag ==="
for group in wwcc-transformer-v5 notification-router-v1; do
  lag=$(docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
    --group $group --describe 2>/dev/null | \
    awk 'NR>1 {sum += $5} END {print sum+0}')
  if [ "$lag" = "" ] || [ "$lag" = "0" ]; then
    echo "✅ $group: 0 lag"
  elif [ "$lag" -lt 10 ]; then
    echo "✅ $group: $lag lag"
  elif [ "$lag" -lt 100 ]; then
    echo "⚠️  $group: $lag lag"
  else
    echo "❌ $group: $lag lag"
  fi
done

