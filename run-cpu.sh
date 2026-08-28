#!/usr/bin/env bash
# Runs the CPU solver from the repository root (pieces.csv is read by relative path).
#
# Prerequisites, once:
#   mvn clean package
#   mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f cp.txt ]; then
    echo "ERROR: cp.txt not found. Run: mvn dependency:build-classpath -Dmdep.outputFile=cp.txt" >&2
    exit 1
fi
if [ ! -d target/classes ]; then
    echo "ERROR: target/classes not found. Run: mvn clean package" >&2
    exit 1
fi

exec java -cp "target/classes:$(cat cp.txt)" dk.puzzle.blackwood.BlackwoodSolver "$@"
