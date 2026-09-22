@echo off
title Shop Diagnostics Web Server
echo Starting the Shop Diagnostics server using the bundled JRE...
echo.

start http://localhost:4568

"jre\bin\java.exe" -jar "ready-repair.jar"

pause