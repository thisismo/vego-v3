#!/usr/bin/env zsh

# Workflow 4 — runs the post-commit RAG indexer (a short-lived CLI, no server).
# Invoked by .git/hooks/post-commit as: run-indexer.sh <repoPath> <commitHash>
#
# Build the executable jar once with:
#   ./kotlin package -m indexer

# Resolve the absolute path to the directory containing this script
DIR="$(cd "$(dirname "$0")" && pwd)"

# Replace the shell process with the Java process executing the indexer JAR, forwarding all args
exec java -jar "$DIR/build/tasks/_indexer_executableJarJvm/indexer-jvm-executable.jar" "$@"
