#!/bin/bash
echo "🐛 Starting Any Cloud Management in debug mode..."
export SPRING_PROFILES_ACTIVE=dev
export JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:8080"
./gradlew anycloud:bootRun
