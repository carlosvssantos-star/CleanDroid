#!/bin/sh
# Gradle start up script (wrapper slim — baixa o Gradle 8.7 no CI)
APP_BASE_NAME=`basename "$0"`
APP_HOME=`cd "${0%/*}" >/dev/null; pwd -P`
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
