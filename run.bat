@echo off
setlocal
if exist "%~dp0MoliFox.exe" (
    "%~dp0MoliFox.exe" %*
    exit /b %errorlevel%
)
call "%~dp0gradlew.bat" -p "%~dp0" :desktop-app:run %*
