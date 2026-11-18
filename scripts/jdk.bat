@echo off
rem JDK embarque : garantit la presence du JDK Windows du projet (jdk\windows-x64) et definit JAVA_HOME.
rem Appele par start.bat et build.bat :  call scripts\jdk.bat
rem
rem Le telechargement n'a lieu que si le JDK est absent ou d'une autre version que celle de jdk.env ;
rem l'archive est verifiee par SHA-256 avant d'etre decompressee. Outils requis : curl et tar (Windows 10+).

for %%I in ("%~dp0..") do set "CADESK_ROOT=%%~fI"
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%CADESK_ROOT%\jdk.env") do set "%%A=%%B"

set "JDK_DIR=%CADESK_ROOT%\jdk\windows-x64"
set "JDK_INSTALLED="
if exist "%JDK_DIR%\.version" set /p JDK_INSTALLED=<"%JDK_DIR%\.version"
if "%JDK_INSTALLED%"=="%JDK_VERSION%" goto :ready

echo JDK %JDK_VERSION% (windows) absent du projet : telechargement...
set "JDK_DL=%CADESK_ROOT%\jdk\.download"
if not exist "%JDK_DL%" mkdir "%JDK_DL%"
set "JDK_ZIP=%JDK_DL%\jdk-windows.zip"
curl.exe -fL --retry 3 -sS -o "%JDK_ZIP%" "%JDK_WINDOWS_URL%"
if errorlevel 1 (
  echo Telechargement impossible : %JDK_WINDOWS_URL%
  exit /b 1
)
set "JDK_HASH="
for /f "skip=1 delims=" %%H in ('certutil -hashfile "%JDK_ZIP%" SHA256') do if not defined JDK_HASH set "JDK_HASH=%%H"
set "JDK_HASH=%JDK_HASH: =%"
if /i not "%JDK_HASH%"=="%JDK_WINDOWS_SHA256%" (
  echo Empreinte SHA-256 inattendue : archive supprimee, rien n'a ete installe.
  del "%JDK_ZIP%"
  exit /b 1
)
if exist "%JDK_DIR%" rmdir /s /q "%JDK_DIR%"
mkdir "%JDK_DIR%"
tar.exe -xf "%JDK_ZIP%" -C "%JDK_DIR%" --strip-components=1
if errorlevel 1 (
  echo Decompression impossible : %JDK_ZIP%
  exit /b 1
)
>"%JDK_DIR%\.version" echo %JDK_VERSION%
del "%JDK_ZIP%"
rmdir "%JDK_DL%" 2>nul
echo JDK %JDK_VERSION% (windows) installe dans jdk\windows-x64

:ready
set "JAVA_HOME=%JDK_DIR%"
exit /b 0
