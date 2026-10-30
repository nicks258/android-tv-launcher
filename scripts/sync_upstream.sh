#!/usr/bin/env bash
set -e

UPSTREAM_URL="${1:-https://github.com/aradhyas8/Plume_Launcher.git}"
UPSTREAM_BRANCH="${2:-main}"
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)

echo "==> Current branch: $CURRENT_BRANCH"
echo "==> Upstream repository: $UPSTREAM_URL ($UPSTREAM_BRANCH)"

# Ensure upstream remote exists
if ! git remote get-url upstream >/dev/null 2>&1; then
    echo "Adding upstream remote..."
    git remote add upstream "$UPSTREAM_URL"
else
    git remote set-url upstream "$UPSTREAM_URL"
fi

# Fetch upstream
echo "==> Fetching upstream..."
git fetch upstream "$UPSTREAM_BRANCH"

# Check difference
NEW_COMMITS=$(git rev-list --count HEAD..upstream/"$UPSTREAM_BRANCH")
if [ "$NEW_COMMITS" -eq 0 ]; then
    echo "==> Up to date! No new commits from upstream."
    exit 0
fi

echo "==> Found $NEW_COMMITS new commit(s) from upstream:"
git log --oneline --no-merges HEAD..upstream/"$UPSTREAM_BRANCH"

echo ""
echo "==> Merging upstream/$UPSTREAM_BRANCH into $CURRENT_BRANCH..."
if git merge --no-edit upstream/"$UPSTREAM_BRANCH"; then
    echo "==> Clean merge successful!"
else
    echo "==> Merge conflict encountered. Please resolve conflicts and run 'git commit'."
    exit 1
fi

# Run tests if requested
if [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

echo "==> Running unit tests to verify merged changes..."
./gradlew testDebugUnitTest --quiet && echo "==> All unit tests passed!"

