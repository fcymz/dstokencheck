@echo off
rem ---------------------------------------------------------------------------
rem  DeepSeek Balance Board - launcher
rem
rem  Build first:   mvn clean package
rem
rem  NOTE: keep this file ASCII-only. cmd.exe decodes batch files with the OEM
rem  code page, so UTF-8 comments (Chinese text) get mis-decoded and can turn
rem  into stray commands that break the whole script.
rem ---------------------------------------------------------------------------
setlocal
set "JAR=%~dp0target\dstokencheck.jar"

if not exist "%JAR%" (
    echo [ERROR] Jar not found:
    echo         %JAR%
    echo         Run "mvn clean package" in this folder first.
    echo.
    pause
    exit /b 1
)

rem Prefer javaw so no console window stays open; fall back to java.
where javaw >nul 2>nul
if errorlevel 1 (
    start "" java -jar "%JAR%" %*
) else (
    start "" javaw -jar "%JAR%" %*
)

endlocal
