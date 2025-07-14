#!/bin/bash
# bump-version.sh
NEW_VERSION=$1
if [ -z "$NEW_VERSION" ]; then
    echo "Usage: ./bump-version.sh 1.2.0"
    exit 1
fi

# Update gradle.properties
sed -i.bak "s/appVersionName=.*/appVersionName=$NEW_VERSION/" gradle.properties
sed -i.bak "s/appVersionCode=.*/appVersionCode=$(($(date +%s) / 86400))/" gradle.properties

echo "Updated to version $NEW_VERSION"
git add gradle.properties
git commit -m "Bump version to $NEW_VERSION"
git tag "v$NEW_VERSION"
echo "Ready to push: git push origin v$NEW_VERSION"

