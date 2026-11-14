@echo off
REM Double-click to stop all project containers.
title HPG - stopping
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\stop.ps1"
echo.
pause
