@echo off

set AMQ_PORT=${amq.port}
set AMQ_STOMP_PORT=${amq.stomp.port}
set SMTP_HOST=${smtp.host}
set ADMIN_EMAIL=${admin.email}

REM this script sets the environment for the fascinator scripts
set FASCINATOR_HOME=${dir.home}
set CLASSPATH=plugins/*;lib/*

REM Logging directories
set JETTY_LOGS=%FASCINATOR_HOME%\logs\jetty
if exist "%JETTY_LOGS%" goto skipjetty
mkdir "%JETTY_LOGS%"
:skipjetty
set SOLR_LOGS=%FASCINATOR_HOME%\logs\solr
if exist "%SOLR_LOGS%" goto skipsolr
mkdir "%SOLR_LOGS%"
:skipsolr
set ARCHIVE_LOGS=%FASCINATOR_HOME%\logs\archives
if exist "%ARCHIVE_LOGS%" goto skiparchives
mkdir "%ARCHIVE_LOGS%"
:skiparchives

REM find java installation
if not defined JAVA_HOME (
  set KeyName=HKEY_LOCAL_MACHINE\SOFTWARE\JavaSoft\Java Development Kit
  set Cmd=reg query "!KeyName!" /s
  for /f "tokens=2*" %%i in ('%Cmd% ^| findstr "JavaHome" 2^> NUL') do set JAVA_HOME=%%j
)

REM find proxy server
set KeyName=HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Internet Settings
set Cmd=reg query "%KeyName%" /s
for /f "tokens=2*" %%i in ('%Cmd% ^| findstr "ProxyServer" 2^> NUL') do set http_proxy=%%j
for /f "tokens=1,2 delims=:" %%i in ("%http_proxy%") do (
  set PROXY_HOST=%%i
  set PROXY_PORT=%%j
)

REM jvm memory settings
set JVM_OPTS=-XX:MaxPermSize=256m -Xmx512m

REM jetty settings
set JETTY_OPTS=-Djetty.port=${server.port} -Djetty.logs=%JETTY_LOGS% -Djetty.home=${dir.server}/jetty

REM solr settings
set SOLR_OPTS=-Dsolr.solr.home="${dir.solr}" -Djava.util.logging.config.file="${dir.solr}/logging.properties"

REM proxy data
set PROXY_OPTS=-Dhttp.proxyHost=%PROXY_HOST% -Dhttp.proxyPort=%PROXY_PORT% -Dhttp.nonProxyHosts="139.86.*^|*.usq.edu.au^|localhost"

REM directories
set CONFIG_DIRS=-Dfascinator.home="%FASCINATOR_HOME%" -Dportal.home="${dir.portal}" -Dstorage.home="${dir.storage}" -Dserver.address="${server.address}"

REM server details
set SERVER_INFO=-Dserver.address="${server.address}" -Dserver.ip="${server.ip}"

REM additional settings
set EXTRA_OPTS=-Damq.port=%AMQ_PORT% -Damq.stomp.port=%AMQ_STOMP_PORT% -Dsmtp.host="%SMTP_HOST%" -Dadmin.email="%ADMIN_EMAIL%"

set JAVA_OPTS=%JVM_OPTS% %SOLR_OPTS% %PROXY_OPTS% %JETTY_OPTS% %CONFIG_DIRS% %SERVER_INFO% %EXTRA_OPTS%
