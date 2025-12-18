#!/bin/bash
# Install git hooks for the project

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HOOKS_DIR="$PROJECT_ROOT/.git/hooks"

echo "Installing git hooks..."

# Install pre-push hook
cp "$SCRIPT_DIR/pre-push-hook.sh" "$HOOKS_DIR/pre-push"
chmod +x "$HOOKS_DIR/pre-push"

echo "✓ Installed pre-push hook"
echo ""
echo "Hooks installed. Tests will run before each push."

