#!/bin/bash
# To executed in the project's root directory.
# The script expects two parameters: The new version to set and whether dependencies to (JULIE Lab) snapshots are allowed.
# This script performs the following actions given a Maven version number:
# 1. It sets the version to all parent poms (projects with packaging 'pom') relative to the given root pom (jcore-base or jcore-projects, for example).
# 2. It removes all custom <version> tags of the modules so that they inherit the version from their parent.
# 3. It updates the inter-module dependencies:
# 3a. It retrieves the version properties from the root POM that have the form 'artifactId'-version.
# 3b. It identifies all dependencies of all modules that refer to another module.
# 3c. It sets the dependency version to the given version or to the property associated with the dependency artifact following the convention mentioned in 3a.

if [ -z "$1" ] || [ -z "$2" ]; then
  echo "Usage: setProjectVersion <new version> <snapshot dependencies allowed (true/false)>"
  exit 1
fi

if [ ! -f jcore-version-normalizer.jar ]; then
	wget https://oss.sonatype.org/content/repositories/releases/de/julielab/jcore-version-normalizer/1.1.0/jcore-version-normalizer-1.1.0-jar-with-dependencies.jar -O jcore-version-normalizer.jar
fi

java -jar jcore-version-normalizer.jar pom.xml $1 $2
python scripts/updateUIMAVersions.py $1 .
scripts/updateMetaDescriptors.sh