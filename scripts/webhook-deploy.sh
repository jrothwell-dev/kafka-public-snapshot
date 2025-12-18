#!/bin/bash
set -e

# Webhook deployment script for council-kafka-platform
# Called by GitHub webhook on push to main

LOG_FILE="/var/log/council-kafka-deploy.log"
PROJECT_DIR="/path/to/council-kafka-platform"  # UPDATE THIS PATH

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

cd "$PROJECT_DIR"

log "=== Deployment triggered ==="
log "Pulling latest changes..."
git pull origin main

log "Running tests..."
if make test-all; then
  log "Tests passed. Building services..."
  make services-build
  
  log "Restarting services..."
  make services-restart
  
  log "=== Deployment successful ==="
else
  log "=== Tests failed. Deployment aborted ==="
  exit 1
fi

