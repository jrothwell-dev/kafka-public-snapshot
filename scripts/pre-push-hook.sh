#!/bin/bash
# Git pre-push hook - runs all tests before allowing push

echo "🧪 Running tests before push..."
echo ""

# Run all unit tests
cd "$(git rev-parse --show-toplevel)"

FAILED=0

for service in safetyculture-poller wwcc-transformer compliance-notification-router notification-service; do
    echo "Testing $service..."
    cd "services/$service"
    if ! sbt -batch test > /dev/null 2>&1; then
        echo "❌ $service tests FAILED"
        FAILED=1
    else
        echo "✓ $service tests passed"
    fi
    cd ../..
done

echo ""

if [ $FAILED -eq 1 ]; then
    echo "❌ Push rejected - tests failed"
    echo "Run 'make test-all' to see failures"
    exit 1
fi

echo "✅ All tests passed - push allowed"
exit 0

