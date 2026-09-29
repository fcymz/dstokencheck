@echo off
rem ---------------------------------------------------------------------------
rem  DeepSeek Balance Board - launcher
rem
rem  Looks for the jar in two places, in order:
rem    1. target\dstokencheck.jar   (a local "mvn clean package")
rem    2. dstokencheck.jar         (the jar downloaded from GitHub Releases,
rem                                  sitting next to this file)
rem
rem  NOTE: keep this file ASCII-only. cmd.exe decodes batch files with the OEM
rem  code page, so UTF-8 comments (Chinese text) get mis-decoded and can turn
rem  into stray commands that break the whole script.
rem ---------------------------------------------------------------------------
setlocal
set "JAR=%~dp0target\dstokencheck.jar"
if not exist "%JAR%" set "JAR=%~dp0dstokencheck.jar"

if not exist "%JAR%" (
    echo [ERROR] dstokencheck.jar not found. Looked in:
    echo           %~dp0target\dstokencheck.jar
    echo           %~dp0dstokencheck.jar
    echo.
    echo   Either build it here:   mvn clean package
    echo   or download dstokencheck.jar from the GitHub releases page
    echo   and put it in the same folder as this run.bat.
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
