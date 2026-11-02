@echo off
setlocal
set "ROOT=%~dp0"
cd /d "%ROOT%"

rem Windows launcher: delegates to start.sh so Windows / macOS / Linux share one script.
rem Requires bash from Git for Windows (https://git-scm.com/download/win).

set "BASH_EXE="
if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH_EXE=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH_EXE if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "BASH_EXE=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined BASH_EXE if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" set "BASH_EXE=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"

if not defined BASH_EXE (
    for /f "delims=" %%i in ('where bash 2^>nul') do if not defined BASH_EXE set "BASH_EXE=%%i"
)

if not defined BASH_EXE (
    echo [FAIL] bash not found. Install Git for Windows: https://git-scm.com/download/win
    echo        Or run manually: java -jar gradle\wrapper\gradle-wrapper.jar :desktop:run
    exit /b 1
)

"%BASH_EXE%" "%ROOT%start.sh" %*
exit /b %ERRORLEVEL%