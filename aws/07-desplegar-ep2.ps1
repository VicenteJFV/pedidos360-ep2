<#
.SINOPSIS
    Despliega EP2 en la EC2: cluster RabbitMQ de dos nodos y los cuatro
    microservicios con Docker Compose, contra Oracle Autonomous Database.

.DESCRIPCION
    Se corre desde el portatil. Hace, en orden:

      1. Comprueba las credenciales del Learner Lab y enciende la EC2 si esta
         detenida.
      2. Deja el SSH del Security Group abierto solo a tu IP publica actual. Tu
         IP cambia entre sesiones; sin esto, SSH da timeout.
      3. Arma un paquete con el Compose, los Dockerfile, el CODIGO FUENTE de los
         cuatro microservicios, la configuracion del cluster y el wallet, y lo
         sube a la EC2. No sube los JAR: la EC2 compila con Maven dentro de
         Docker. Asi el paquete pesa menos de 1 MB en vez de 144, y las
         dependencias las descarga la EC2 por la red de AWS.
      4. Escribe la contrasena de Oracle en la EC2 por la entrada estandar de
         SSH, nunca en la linea de comandos ni en un archivo del portatil.
      5. Ejecuta despliegue/instalar-en-ec2.sh en la instancia.

    La contrasena de ADMIN es la misma que usa verificar-ep2.ps1 -Oracle: si ya
    la ingresaste ahi, no la vuelve a pedir. Esta cifrada con DPAPI en
    %LOCALAPPDATA%\pedidos360\, atada a tu usuario de Windows.

    Es idempotente: sirve igual para el primer despliegue que para actualizar
    despues de cambiar codigo. Las pruebas no corren en la EC2: correlas antes
    en el portatil (mvn test, o pruebas\verificar-ep2.ps1).

.EJEMPLO
    .\01-credenciales-learner-lab.ps1 ; .\07-desplegar-ep2.ps1
#>
[CmdletBinding()]
param(
    [string]$Instancia = "i-0598cbdf24a3ab50e",
    [string]$Region = "us-east-1",
    [string]$Llave = "",
    [string]$RaizProyecto = (Split-Path $PSScriptRoot -Parent)
)

$ErrorActionPreference = "Stop"

$modulos = @("ms-pedidos360-catalog", "ms-pedidos360-orders", "ms-pedidos360-notify", "ms-pedidos360-rabbitadmin")
$destinoRemoto = "/opt/pedidos360-ep2"

function Paso([string]$Texto) {
    Write-Host ""
    Write-Host "=== $Texto ===" -ForegroundColor Cyan
}

<#
    Ejecuta un comando nativo sin que su stderr aborte el script. ssh, scp y aws
    escriben avisos normales en stderr, y PowerShell 5.1 con Stop los convierte
    en error fatal aunque el comando haya salido bien. Se decide por el codigo
    de salida.
#>
function Invoke-Nativo {
    param([Parameter(Mandatory)][string]$Programa, [string[]]$Argumentos, [switch]$Mostrar)
    $previo = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($Mostrar) {
            & $Programa @Argumentos 2>&1 | ForEach-Object {
                $linea = "$_"
                # Aviso de OpenSSH sobre el intercambio de llaves del servidor: ruido.
                if ($linea -notmatch "post-quantum|store now, decrypt later|openssh\.com/pq|^\*\* ") {
                    Write-Host $linea
                }
            }
            $script:codigo = $LASTEXITCODE
            return
        }
        $salida = & $Programa @Argumentos 2>$null
        $script:codigo = $LASTEXITCODE
        return $salida
    } finally {
        $ErrorActionPreference = $previo
    }
}

function Invoke-Aws {
    param([string[]]$Argumentos)
    $salida = Invoke-Nativo "aws" ($Argumentos + @("--region", $Region, "--output", "json"))
    if ($script:codigo -ne 0) { return $null }
    if (-not $salida) { return @{} }
    return (($salida -join "`n") | ConvertFrom-Json)
}

# ---------------------------------------------------------------------------
Paso "Credenciales del Learner Lab"
# ---------------------------------------------------------------------------

if (-not (Invoke-Aws @("sts", "get-caller-identity"))) {
    throw "Las credenciales no sirven. Inicia el lab y corre .\01-credenciales-learner-lab.ps1"
}
Write-Host "  OK" -ForegroundColor Green

if (-not $Llave) {
    $candidatas = @(
        (Join-Path $PSScriptRoot "pedidos360-key.pem"),
        "C:\dev\pedidos360\aws\pedidos360-key.pem"
    )
    $Llave = $candidatas | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $Llave) { throw "No encuentro pedidos360-key.pem. Indica la ruta con -Llave." }
}
# ServerAliveInterval: en algunas redes una conexion que pasa un rato sin
# trafico se descarta en silencio, y SSH queda esperando para siempre una
# respuesta que no va a llegar. El keepalive mantiene viva la conexion y, si de
# todos modos se corta, hace que SSH lo note y termine.
$opcionesSsh = @("-i", $Llave, "-o", "BatchMode=yes", "-o", "ConnectTimeout=20",
                 "-o", "StrictHostKeyChecking=accept-new",
                 "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=4")

# ---------------------------------------------------------------------------
Paso "Instancia EC2"
# ---------------------------------------------------------------------------

$datos = Invoke-Aws @("ec2", "describe-instances", "--instance-ids", $Instancia)
if (-not $datos) { throw "No encuentro la instancia $Instancia." }
$ec2 = $datos.Reservations[0].Instances[0]

if ($ec2.State.Name -eq "stopped") {
    Write-Host "  esta detenida; encendiendola..." -ForegroundColor Yellow
    Invoke-Aws @("ec2", "start-instances", "--instance-ids", $Instancia) | Out-Null
    Invoke-Nativo "aws" @("ec2", "wait", "instance-running", "--instance-ids", $Instancia, "--region", $Region) | Out-Null
    $ec2 = (Invoke-Aws @("ec2", "describe-instances", "--instance-ids", $Instancia)).Reservations[0].Instances[0]
} elseif ($ec2.State.Name -ne "running") {
    throw "La instancia esta en estado '$($ec2.State.Name)'. Espera a que termine la transicion y reintenta."
}
$ip = $ec2.PublicIpAddress
Write-Host "  $Instancia  $($ec2.InstanceType)  $ip" -ForegroundColor Green

# Perfil de instancia para Session Manager: abre una terminal de la EC2 desde
# la consola de AWS, en el navegador, sin llave SSH ni puerto 22. Es lo que
# permite mostrar los logs desde un PC del instituto. El Learner Lab no deja
# crear roles, pero si usar el que trae: LabInstanceProfile.
if (-not $ec2.IamInstanceProfile) {
    $asociacion = Invoke-Aws @("ec2", "associate-iam-instance-profile", "--instance-id", $Instancia,
                               "--iam-instance-profile", "Name=LabInstanceProfile")
    if ($asociacion) {
        Write-Host "  perfil LabInstanceProfile asignado (Session Manager)" -ForegroundColor Green
    } else {
        Write-Host "  no se pudo asignar LabInstanceProfile: Session Manager no estara disponible" -ForegroundColor Yellow
    }
} else {
    Write-Host "  perfil de instancia: $(($ec2.IamInstanceProfile.Arn -split '/')[-1])" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------------------
Paso "SSH solo desde tu IP"
# ---------------------------------------------------------------------------

$miIp = (Invoke-RestMethod -Uri "https://checkip.amazonaws.com" -TimeoutSec 15).Trim()
$sg = $ec2.SecurityGroups[0].GroupId
$reglas = (Invoke-Aws @("ec2", "describe-security-groups", "--group-ids", $sg)).SecurityGroups[0].IpPermissions |
          Where-Object { $_.FromPort -eq 22 -and $_.ToPort -eq 22 }
$actuales = @($reglas | ForEach-Object { $_.IpRanges } | ForEach-Object { $_.CidrIp })

if ($actuales -notcontains "$miIp/32") {
    Invoke-Aws @("ec2", "authorize-security-group-ingress", "--group-id", $sg,
                 "--protocol", "tcp", "--port", "22", "--cidr", "$miIp/32") | Out-Null
    Write-Host "  autorizada $miIp" -ForegroundColor Green
}
# Las IP anteriores se quitan: una regla de SSH por cada red desde la que alguna
# vez te conectaste es una puerta que nadie recuerda que esta abierta.
foreach ($cidr in $actuales | Where-Object { $_ -ne "$miIp/32" }) {
    Invoke-Aws @("ec2", "revoke-security-group-ingress", "--group-id", $sg,
                 "--protocol", "tcp", "--port", "22", "--cidr", $cidr) | Out-Null
    Write-Host "  revocada $cidr" -ForegroundColor DarkGray
}
Write-Host "  puerto 22 abierto solo a $miIp" -ForegroundColor Green

# ---------------------------------------------------------------------------
Paso "Puertos publicos"
# ---------------------------------------------------------------------------
# 80 y 8080 los alcanza el API Gateway, que llama desde Internet: por ahi pasan
# el frontend (/app/...) y el BFF (/api/...). 15672 y 15673 son RabbitMQ
# Management, abierto como en las guias del curso y protegido por su login.

$publicos = @(
    @{ Desde = 80;    Hasta = 80;    Para = "frontend (destino del API Gateway)" },
    @{ Desde = 8080;  Hasta = 8080;  Para = "BFF (destino del API Gateway)" },
    @{ Desde = 15672; Hasta = 15673; Para = "RabbitMQ Management, nodos 1 y 2" }
)
$reglasSg = (Invoke-Aws @("ec2", "describe-security-groups", "--group-ids", $sg)).SecurityGroups[0].IpPermissions
foreach ($p in $publicos) {
    $existe = $reglasSg | Where-Object {
        $_.FromPort -eq $p.Desde -and $_.ToPort -eq $p.Hasta -and
        (@($_.IpRanges | ForEach-Object { $_.CidrIp }) -contains "0.0.0.0/0")
    }
    if (-not $existe) {
        $permiso = "IpProtocol=tcp,FromPort=$($p.Desde),ToPort=$($p.Hasta),IpRanges=[{CidrIp=0.0.0.0/0}]"
        Invoke-Aws @("ec2", "authorize-security-group-ingress", "--group-id", $sg,
                     "--ip-permissions", $permiso) | Out-Null
    }
    $rango = if ($p.Desde -eq $p.Hasta) { "$($p.Desde)" } else { "$($p.Desde)-$($p.Hasta)" }
    Write-Host ("  {0,-11} {1}" -f $rango, $p.Para) -ForegroundColor Green
}

$listo = $false
for ($i = 0; $i -lt 18; $i++) {
    Invoke-Nativo "ssh" ($opcionesSsh + @("ec2-user@$ip", "true")) | Out-Null
    if ($script:codigo -eq 0) { $listo = $true; break }
    Start-Sleep -Seconds 10
}
if (-not $listo) { throw "SSH no responde en $ip despues de 3 minutos." }

# ---------------------------------------------------------------------------
Paso "Contrasena de Oracle"
# ---------------------------------------------------------------------------

$wallet = Join-Path $RaizProyecto "wallet"
if (-not (Test-Path (Join-Path $wallet "cwallet.sso"))) {
    throw "No esta el wallet en $wallet. Descargalo desde la consola de OCI."
}

$archivoCredencial = Join-Path $env:LOCALAPPDATA "pedidos360\oracle-admin.xml"
if (Test-Path $archivoCredencial) {
    $credencial = Import-Clixml $archivoCredencial
    Write-Host "  se usa la guardada por verificar-ep2.ps1" -ForegroundColor Green
} else {
    $secreto = Read-Host "Contrasena de ADMIN de la base pedidos360" -AsSecureString
    $credencial = New-Object System.Management.Automation.PSCredential("ADMIN", $secreto)
    New-Item -ItemType Directory -Force -Path (Split-Path $archivoCredencial) | Out-Null
    $credencial | Export-Clixml $archivoCredencial
}
$clave = $credencial.GetNetworkCredential().Password
if ($clave.Contains("'")) {
    # El valor va entre comillas simples para que Compose no interprete un $ de
    # la contrasena como una variable. Una comilla simple romperia ese cierre.
    throw "La contrasena contiene una comilla simple ('), que el archivo de entorno de Compose no admite. Cambiala en OCI."
}

# ---------------------------------------------------------------------------
Paso "Paquete"
# ---------------------------------------------------------------------------

$staging = Join-Path $env:TEMP ("pedidos360-ep2-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$paquete = "$staging.tgz"

try {
    New-Item -ItemType Directory -Path $staging | Out-Null

    $archivos = @(
        "despliegue\docker-compose.yml",
        "despliegue\instalar-en-ec2.sh",
        "despliegue\lanzar-instalacion.sh",
        "rabbitmq-cluster\rabbitmq-1\rabbitmq.conf",
        "rabbitmq-cluster\rabbitmq-2\rabbitmq.conf"
    )
    foreach ($m in $modulos) { $archivos += @("$m\Dockerfile", "$m\pom.xml") }
    $archivos += @("bff\Dockerfile", "bff\pom.xml")
    $archivos += @("frontend\Dockerfile", "frontend\nginx.conf", "frontend\package.json",
                   "frontend\package-lock.json", "frontend\angular.json",
                   "frontend\tsconfig.json", "frontend\tsconfig.app.json")

    foreach ($rel in $archivos) {
        $origen = Join-Path $RaizProyecto $rel
        if (-not (Test-Path $origen)) { throw "Falta $origen" }
        $destino = Join-Path $staging $rel
        New-Item -ItemType Directory -Force -Path (Split-Path $destino) | Out-Null

        if ($rel -match "\.(sh|yml|conf)$|Dockerfile$") {
            # Todo esto se lee en Linux: se fuerza LF y UTF-8 sin BOM. Un "\r"
            # rompe bash y los RUN del Dockerfile.
            $texto = [IO.File]::ReadAllText($origen).Replace("`r`n", "`n")
            [IO.File]::WriteAllText($destino, $texto, (New-Object Text.UTF8Encoding($false)))
        } else {
            Copy-Item $origen $destino
        }
    }

    # El codigo de produccion, sin las pruebas ni target/: es lo unico que
    # necesita la etapa de compilacion del Dockerfile.
    $carpetasFuente = @($modulos | ForEach-Object { "$_\src\main" }) +
                      @("bff\src\main", "frontend\src", "frontend\public")
    foreach ($rel in $carpetasFuente) {
        $fuente = Join-Path $RaizProyecto $rel
        if (-not (Test-Path $fuente)) { throw "Falta $fuente" }
        $destino = Join-Path $staging $rel
        New-Item -ItemType Directory -Force -Path (Split-Path $destino) | Out-Null
        Copy-Item $fuente $destino -Recurse
    }
    # node_modules y dist NO viajan: los genera la imagen en la EC2. Son cientos
    # de MB que con una subida lenta tardarian decenas de minutos.

    Copy-Item $wallet (Join-Path $staging "wallet") -Recurse

    $tar = Join-Path $env:SystemRoot "System32\tar.exe"
    Invoke-Nativo $tar @("-czf", $paquete, "-C", $staging, ".") | Out-Null
    if ($script:codigo -ne 0) { throw "No se pudo armar el paquete." }
    $mb = [Math]::Round((Get-Item $paquete).Length / 1MB, 1)
    Write-Host "  $mb MB" -ForegroundColor Green

    # -----------------------------------------------------------------------
    Paso "Subida"
    # -----------------------------------------------------------------------

    Invoke-Nativo "scp" ($opcionesSsh + @($paquete, "ec2-user@${ip}:/tmp/pedidos360-ep2.tgz")) | Out-Null
    if ($script:codigo -ne 0) { throw "scp fallo." }

    # Se reemplaza el codigo y el wallet, pero NO despliegue/.env: ahi estan la
    # cookie del cluster y la contrasena de RabbitMQ, que deben sobrevivir.
    $extraer = "sudo mkdir -p $destinoRemoto && " +
               "sudo rm -rf $destinoRemoto/wallet $destinoRemoto/ms-pedidos360-* $destinoRemoto/bff $destinoRemoto/frontend && " +
               "sudo tar -xzf /tmp/pedidos360-ep2.tgz -C $destinoRemoto && " +
               "rm -f /tmp/pedidos360-ep2.tgz"
    Invoke-Nativo "ssh" ($opcionesSsh + @("ec2-user@$ip", $extraer)) | Out-Null
    if ($script:codigo -ne 0) { throw "No se pudo extraer el paquete en la EC2." }
    Write-Host "  en $destinoRemoto" -ForegroundColor Green
}
finally {
    # El paquete lleva el wallet, que abre la base sin contrasena.
    Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $paquete -Force -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------------------
Paso "Credenciales de Oracle en la EC2"
# ---------------------------------------------------------------------------
# La contrasena viaja por la entrada estandar de SSH, cifrada en el canal. No
# aparece en la linea de comandos -que cualquier proceso del host podria ver
# con ps- ni se escribe en ningun archivo del portatil.

$contenido = "ORACLE_USER=$($credencial.UserName)`nORACLE_PASSWORD='$clave'`n"
$previoEncoding = $OutputEncoding
$OutputEncoding = New-Object Text.UTF8Encoding($false)
try {
    $previo = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    # tr quita el \r que PowerShell agrega al final al pasar texto a un programa.
    $contenido | & ssh @opcionesSsh "ec2-user@$ip" "tr -d '\r' | sudo install -m 600 /dev/stdin $destinoRemoto/despliegue/oracle.env" 2>$null
    $codigoEscritura = $LASTEXITCODE
    $ErrorActionPreference = $previo
} finally {
    $OutputEncoding = $previoEncoding
    $clave = $null
    $contenido = $null
}
if ($codigoEscritura -ne 0) { throw "No se pudo escribir oracle.env en la EC2." }
Write-Host "  oracle.env (600)" -ForegroundColor Green

# ---------------------------------------------------------------------------
Paso "Instalacion en la EC2"
# ---------------------------------------------------------------------------

# La instalacion corre desacoplada en la EC2 (lanzar-instalacion.sh) y aqui solo
# se consulta su log con conexiones cortas. Si la red corta una consulta, la
# siguiente la retoma; la instalacion no se entera.
Invoke-Nativo "ssh" ($opcionesSsh + @("ec2-user@$ip", "sudo bash $destinoRemoto/despliegue/lanzar-instalacion.sh")) | Out-Null
if ($script:codigo -ne 0) { throw "No se pudo lanzar la instalacion en la EC2." }

$consulta = 'sudo cat /tmp/instalacion.log 2>/dev/null; echo __CODIGO__$(sudo cat /tmp/instalacion.codigo 2>/dev/null)'
$mostradas = 0
$codigoInstalacion = $null
$limite = (Get-Date).AddMinutes(20)

while ($null -eq $codigoInstalacion -and (Get-Date) -lt $limite) {
    Start-Sleep -Seconds 5
    $salida = Invoke-Nativo "ssh" ($opcionesSsh + @("ec2-user@$ip", $consulta))
    if ($script:codigo -ne 0 -or -not $salida) { continue }

    $lineas = @($salida)
    $marca = $lineas | Where-Object { $_ -like "__CODIGO__*" } | Select-Object -Last 1
    $log = @($lineas | Where-Object { $_ -notlike "__CODIGO__*" })

    for ($n = $mostradas; $n -lt $log.Count; $n++) { Write-Host $log[$n] }
    $mostradas = $log.Count

    $valor = "$marca".Replace("__CODIGO__", "").Trim()
    if ($valor -match "^\d+$") { $codigoInstalacion = [int]$valor }
}

Write-Host ""
if ($null -eq $codigoInstalacion) {
    Write-Host "La instalacion no termino en 20 minutos. Su log esta en la EC2: /tmp/instalacion.log" -ForegroundColor Red
    exit 1
}
if ($codigoInstalacion -ne 0) {
    Write-Host "La instalacion termino con errores (codigo $codigoInstalacion)." -ForegroundColor Red
    Write-Host "Para ver por que no arranco un servicio:" -ForegroundColor DarkGray
    Write-Host "  ssh -i `"$Llave`" ec2-user@$ip `"sudo docker logs --tail 50 ms-pedidos360-orders`"" -ForegroundColor DarkGray
    exit 1
}

# ---------------------------------------------------------------------------
Paso "Rutas del frontend en el API Gateway"
# ---------------------------------------------------------------------------
# El frontend corre en la EC2, pero se entra por el API Gateway en /app/. Asi
# tiene HTTPS con certificado valido -Entra ID no acepta otra cosa fuera de
# localhost- y comparte origen con /api/..., sin CORS en el navegador. Las rutas
# son publicas: un frontend es HTML y JS que cualquiera puede descargar; lo que
# exige token son las rutas /api/....

$api = (Invoke-Aws @("apigatewayv2", "get-apis")).Items | Where-Object { $_.Name -eq "API_Gateway_Entrega1" } | Select-Object -First 1
if (-not $api) { throw "No encuentro el API Gateway API_Gateway_Entrega1." }
$rutasGateway = (Invoke-Aws @("apigatewayv2", "get-routes", "--api-id", $api.ApiId, "--max-results", "500")).Items

$rutasFrontend = @(
    @{ Clave = "GET /app";          Destino = "http://$ip/app/" },
    @{ Clave = "GET /app/{proxy+}"; Destino = "http://$ip/app/{proxy}" }
)
foreach ($r in $rutasFrontend) {
    if ($rutasGateway | Where-Object { $_.RouteKey -eq $r.Clave }) {
        Write-Host ("  {0,-20} ya existia" -f $r.Clave) -ForegroundColor DarkGray
        continue
    }
    $integracion = Invoke-Aws @("apigatewayv2", "create-integration", "--api-id", $api.ApiId,
        "--integration-type", "HTTP_PROXY", "--integration-method", "GET",
        "--integration-uri", $r.Destino, "--payload-format-version", "1.0")
    if (-not $integracion) { throw "No se pudo crear la integracion de $($r.Clave)." }
    Invoke-Aws @("apigatewayv2", "create-route", "--api-id", $api.ApiId,
        "--route-key", $r.Clave, "--authorization-type", "NONE",
        "--target", "integrations/$($integracion.IntegrationId)") | Out-Null
    Write-Host ("  {0,-20} -> {1}" -f $r.Clave, $r.Destino) -ForegroundColor Green
}

$urlFrontend = "$($api.ApiEndpoint)/Desarrollo/app/"

Write-Host ""
Write-Host "DESPLIEGUE COMPLETO" -ForegroundColor Green
Write-Host ""
Write-Host "  Frontend:             $urlFrontend" -ForegroundColor Cyan
Write-Host "  RabbitMQ Management:  http://${ip}:15672  (nodo 2: :15673)" -ForegroundColor Cyan
Write-Host ""
Write-Host "Logs de productor y consumidor (terminal de la EC2):" -ForegroundColor Cyan
Write-Host "  .\08-tunel-demo.ps1 -Logs" -ForegroundColor Cyan
Write-Host "Postman contra la API administradora (127.0.0.1:8084), por tunel:" -ForegroundColor Cyan
Write-Host "  .\08-tunel-demo.ps1" -ForegroundColor Cyan
Write-Host ""
