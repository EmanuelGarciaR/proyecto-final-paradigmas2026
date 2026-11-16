@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "WRAPPER_DIR=%SCRIPT_DIR%.sbt-wrapper"
set "WRAPPER_JAR=%WRAPPER_DIR%\sbt-launch.jar"
if not exist "%WRAPPER_JAR%" (
  mkdir "%WRAPPER_DIR%" 2>nul
  curl.exe -sSL -o "%WRAPPER_JAR%" https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/1.10.2/sbt-launch-1.10.2.jar
)
mkdir "%WRAPPER_DIR%\tmp" 2>nul
pushd "%SCRIPT_DIR%"
java -Xms512m -Xmx2048m ^
  "-Djava.io.tmpdir=%WRAPPER_DIR%\tmp" ^
  "-Dsbt.boot.directory=%WRAPPER_DIR%\boot" ^
  "-Dsbt.global.base=%WRAPPER_DIR%\global" ^
  "-Dsbt.ivy.home=%WRAPPER_DIR%\ivy2" ^
  "-Dsbt.coursier.home=%WRAPPER_DIR%\coursier" ^
  -jar "%WRAPPER_JAR%" %*
set "EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %EXIT_CODE%
