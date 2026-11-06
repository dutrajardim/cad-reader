@echo off
setlocal DisableDelayedExpansion
title CAD Reader
set "CAD_READER_JAR=%~dp0cad-reader.jar"

where java >nul 2>&1
if errorlevel 1 (
    echo Java nao encontrado. Instale Java 11 ou superior e configure o PATH.
    pause
    exit /b 1
)

if exist "%CAD_READER_JAR%" goto launch

echo Baixando CAD Reader...
powershell.exe -NoLogo -NoProfile -Command "$ErrorActionPreference = 'Stop'; $destination = $env:CAD_READER_JAR; $temporary = $destination + '.' + [guid]::NewGuid().ToString('N') + '.download'; try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12; $proxy = [Net.WebRequest]::DefaultWebProxy; if ($null -ne $proxy) { $proxy.Credentials = [Net.CredentialCache]::DefaultNetworkCredentials }; Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/dutrajardim/cad-reader/releases/latest/download/cad-reader.jar' -OutFile $temporary; if ((Get-Item -LiteralPath $temporary).Length -eq 0) { throw 'Download vazio.' }; Move-Item -LiteralPath $temporary -Destination $destination } catch { Write-Host ('Falha ao baixar: ' + $_.Exception.Message); exit 1 } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }"
if errorlevel 1 (
    echo Verifique a internet, o proxy e a permissao para gravar nesta pasta.
    pause
    exit /b 1
)

:launch
echo Iniciando CAD Reader com o proxy do sistema...
java -Djava.net.useSystemProxies=true -jar "%CAD_READER_JAR%" %*
set "CAD_READER_EXIT=%ERRORLEVEL%"
if not "%CAD_READER_EXIT%"=="0" pause
exit /b %CAD_READER_EXIT%
