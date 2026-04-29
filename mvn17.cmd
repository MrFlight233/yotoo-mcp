@echo off
setlocal
set "JAVA_HOME=D:\develop\Java\jdk-17.0.18"
set "PATH=%JAVA_HOME%\bin;%PATH%"
mvn %*
endlocal
