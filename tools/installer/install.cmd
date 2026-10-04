@echo off
rem Entry point the setup package runs after unpacking itself.
rem
rem The argument expansion deliberately sits at the top level, not inside an if/for block: cmd
rem parses a block before expanding it, so a path or name containing ")" (Program Files (x86),
rem or any user's folder) would end the block early and produce a syntax error.
rem
rem DSTOKENCHECK_SETUP_ARGS exists so the packaged installer can be driven from a script (that is
rem how it is tested) without going through the dialogs.
powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0install.ps1" %DSTOKENCHECK_SETUP_ARGS%
exit /b %errorlevel%
