#!/usr/bin/env zsh

# Resolve the absolute path to the directory containing this script
DIR="$(cd "$(dirname "$0")" && pwd)"

# Replace the shell process with the Java process executing the target JAR
exec java -jar "$DIR/build/tasks/_analyst_executableJarJvm/analyst-jvm-executable.jar"