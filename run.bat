@echo off
title Shop Diagnostics Web Server
echo Starting the Shop Diagnostics server using the bundled JRE...
echo.

:: Automatically open the default web browser
start http://localhost:4568


"jre\bin\java.exe" -cp "lib\*;ready_repair.jar" server.ShopServer

pause