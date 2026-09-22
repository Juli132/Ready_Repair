@echo off
title Shop Diagnostics Web Server
cd /d "%~dp0"
echo Starting the Shop Diagnostics server from USB...
echo.

start "" http://localhost:4568

"%~dp0jre\bin\java.exe" -jar "%~dp0ready-repair.jar"

pause