@echo off
setlocal
rem Keep GRADLE_VERSION in sync with gradle/wrapper/gradle-wrapper.properties.
set "GRADLE_VERSION=9.6.0"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "INSTALL_DIR=%GRADLE_USER_HOME%\wrapper\dists\local-intercom\gradle-%GRADLE_VERSION%"
set "GRADLE_HOME=%INSTALL_DIR%\gradle-%GRADLE_VERSION%"
set "GRADLE_BIN=%GRADLE_HOME%\bin\gradle.bat"

if not exist "%GRADLE_BIN%" (
  if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $archive=Join-Path '%INSTALL_DIR%' 'gradle-%GRADLE_VERSION%-bin.zip'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $archive; Expand-Archive -Path $archive -DestinationPath '%INSTALL_DIR%' -Force; Remove-Item $archive"
  if errorlevel 1 exit /b 1
)

call "%GRADLE_BIN%" %*
exit /b %errorlevel%
