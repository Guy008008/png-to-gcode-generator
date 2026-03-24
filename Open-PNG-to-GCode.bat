@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"

echo Launching PNG to G-code Generator...
echo.

where mvn >nul 2>nul
if errorlevel 1 (
    echo Maven was not found on PATH.
    echo Install Maven, then double-click this file again.
    echo.
    pause
    exit /b 1
)

mvn javafx:run
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo Launch failed with exit code %EXIT_CODE%.
    pause
)

exit /b %EXIT_CODE%