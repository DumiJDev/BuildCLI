@echo off
setlocal
set BASENAME=buildcli_
chcp 65001 2>nul >nul

set java_exe=java.exe

if defined JAVA_HOME (
    set "java_exe=%JAVA_HOME%\bin\java.exe"
)

setlocal EnableDelayedExpansion
pushd "%~dp0"
if exist buildcli.jar (
    set BASENAME=buildcli
    goto skipversioned
)
set max=0
for /f "tokens=1* delims=-_.0" %%A in ('dir /b /a-d %BASENAME%*.jar') do if %%~B gtr !max! set max=%%~nB
:skipversioned
popd
setlocal DisableDelayedExpansion

:load
"%java_exe%" -Xmx1024M -Dfile.encoding=UTF8 --enable-preview --add-modules jdk.incubator.vector -jar "%~dp0%BASENAME%%max%.jar" %*

rem Pause when ran non interactively
for %%i in (%cmdcmdline%) do if /i "%%~i"=="/c" pause & exit /b