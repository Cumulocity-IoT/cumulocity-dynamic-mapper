#!/bin/zsh
SCRIPT_DIR="${0:A:h}"
REPO_ROOT="${SCRIPT_DIR:h:h:h}"
SERVICE_DIR="$REPO_ROOT/dynamic-mapper-service"

# Resolve the JVM Maven itself will use to compile/build the classpath, so the
# `java` that runs the app always matches the bytecode version `mvn` just produced
# (a JAVA_HOME left pointing at an older JDK than the one Maven/sdkman actually
# uses causes a cryptic UnsupportedClassVersionError at startup otherwise).
MVN_JAVA_HOME="$(mvn -f "$SERVICE_DIR/pom.xml" -version 2>/dev/null | sed -n 's/.*runtime: \(.*\)$/\1/p')"
JAVA_BIN="${JAVA_BIN:-${MVN_JAVA_HOME:+$MVN_JAVA_HOME/bin/java}}"
JAVA_BIN="${JAVA_BIN:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
JAVA_BIN="${JAVA_BIN:-$(command -v java)}"
DEBUG_PORT="${DEBUG_PORT:-54346}"
DEBUG_SUSPEND="${DEBUG_SUSPEND:-n}"
C8Y_BASE_URL="${C8Y_BASE_URL:-http://localhost:8888}"

CP_FILE=$(mktemp -t dynamic-mapper-cp)
ARGFILE=$(mktemp -t dynamic-mapper-argfile)
trap 'rm -f "$CP_FILE" "$ARGFILE"' EXIT

mvn -q -f "$SERVICE_DIR/pom.xml" dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
CP="$SERVICE_DIR/target/classes:$(cat "$CP_FILE")"
printf -- '-cp "%s"\n' "$CP" > "$ARGFILE"

exec /usr/bin/env "$JAVA_BIN" \
  -agentlib:jdwp=transport=dt_socket,server=y,suspend="$DEBUG_SUSPEND",address=localhost:"$DEBUG_PORT" \
  -DC8Y.baseURL="$C8Y_BASE_URL" \
  -Dspring.profiles.active=dev \
  "@$ARGFILE" \
  dynamic.mapper.App
