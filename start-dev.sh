#!/bin/bash
echo "🚀 Starting Any Cloud Management in development mode..."
export SPRING_PROFILES_ACTIVE=dev
./gradlew anycloud:bootRun
