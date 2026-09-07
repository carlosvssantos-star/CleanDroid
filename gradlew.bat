@rem Gradle startup script for Windows (wrapper slim)
@if "%DEBUG%"=="" @echo off
set APP_HOME=%DIRNAME%..
@rem Resolve APP_HOME
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%..
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
java -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
