@echo off
title Shop Diagnostics Web Server
echo Starting the Shop Diagnostics server using the bundled JRE...
echo.

:: Automatically open the default web browser
start http://localhost:4568

:: Use the bundled JRE to run the JAR alongside the lib folder dependencies
"jre\bin\java.exe" -cp "lib\*;ready_repair-1.0.jar" server.ShopServer

pause