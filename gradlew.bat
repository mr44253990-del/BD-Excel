@rem Gradient wrapper batch (minimal).
@if "%DEBUG%"=="" @echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

where gradle >nul 2>&1
if %errorlevel% equ 0 (
  gradle %*
) else (
  echo system gradle not found >&2
  exit /b 1
)
