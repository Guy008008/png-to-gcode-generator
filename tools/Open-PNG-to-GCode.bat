@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "APP_DIR=%%~fI"
cd /d "%APP_DIR%"

call "%APP_DIR%\Open-PNG-to-GCode.bat"
exit /b %ERRORLEVEL%