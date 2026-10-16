<#
.SINOPSIS
    Abre el tunel SSH para hacer la demostracion de EP2 contra la EC2, o muestra
    en vivo los logs del productor y el consumidor.

.DESCRIPCION
    En la EC2 todo lo de EP2 escucha solo en 127.0.0.1: RabbitMQ Management, la
    API administradora y los microservicios no son alcanzables desde Internet.
    El tunel los trae a los mismos puertos de tu portatil, de modo que:

      - Management queda en http://localhost:15672 (nodo 1) y :15673 (nodo 2).
      - La coleccion de Postman y pruebas\DEMOSTRACION.md funcionan sin cambios,
        porque apuntan a 127.0.0.1:8081, :8082 y :8084.

    El tunel se mantiene abierto mientras la ventana siga abierta; Ctrl+C lo
    cierra.

    Antes hay que detener lo que ocupe esos puertos en el portatil: el cluster
    local de desarrollo y los microservicios que estuvieran corriendo.

.EJEMPLO
    # Ventana 1: el tunel
    .\08-tunel-demo.ps1

.EJEMPLO
    # Ventana 2: logs de pedidos (productor) y notify (consumidor)
    .\08-tunel-demo.ps1 -Logs
#>
[CmdletBinding()]
param(
    [switch]$Logs,
    [string]$Ip = "32.194.66.142",
    [string]$Llave = ""
)

$ErrorActionPreference = "Stop"

if (-not $Llave) {
    $Llave = @((Join-Path $PSScriptRoot "pedidos360-key.pem"), "C:\dev\pedidos360\aws\pedidos360-key.pem") |
             Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $Llave) { throw "No encuentro pedidos360-key.pem. Indica la ruta con -Llave." }
}
$opcionesSsh = @("-i", $Llave, "-o", "ConnectTimeout=20", "-o", "StrictHostKeyChecking=accept-new",
                 "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=4")

# ---------------------------------------------------------------------------
# Logs
# ---------------------------------------------------------------------------

if ($Logs) {
    Write-Host ""
    Write-Host "Logs de ms-pedidos360-orders (productor) y ms-pedidos360-notify (consumidor)." -ForegroundColor Cyan
    Write-Host "Ctrl+C para salir. Los servicios siguen corriendo." -ForegroundColor DarkGray
    Write-Host ""
    # -t asigna una terminal: sin ella, Ctrl+C no le llega al comando remoto.
    $ErrorActionPreference = "Continue"
    # logs-demo lo instala el despliegue. Es el mismo comando que se usa en la
    # terminal de Session Manager, asi que la salida se ve igual por los dos lados.
    & ssh @opcionesSsh -t "ec2-user@$Ip" "logs-demo"
    exit $LASTEXITCODE
}

# ---------------------------------------------------------------------------
# Tunel
# ---------------------------------------------------------------------------

$puertos = [ordered]@{
    15672 = "RabbitMQ Management, nodo 1"
    15673 = "RabbitMQ Management, nodo 2"
    8081  = "ms-pedidos360-orders"
    8082  = "ms-pedidos360-catalog"
    8083  = "ms-pedidos360-notify (health)"
    8084  = "ms-pedidos360-rabbitadmin"
}

$ocupados = @()
foreach ($p in $puertos.Keys) {
    $escucha = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($escucha) {
        $proceso = (Get-Process -Id ($escucha | Select-Object -First 1).OwningProcess -ErrorAction SilentlyContinue).ProcessName
        $ocupados += "  $p  ocupado por '$proceso'  ($($puertos[$p]))"
    }
}
if ($ocupados.Count -gt 0) {
    Write-Host ""
    Write-Host "Hay puertos ocupados en este portatil:" -ForegroundColor Red
    $ocupados | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    Write-Host ""
    Write-Host "Si es el cluster local:   cd ..\rabbitmq-cluster ; docker compose stop" -ForegroundColor DarkGray
    Write-Host "Si son microservicios locales, cierra sus terminales (Ctrl+C)." -ForegroundColor DarkGray
    exit 1
}

# Las credenciales de Management se generaron en la EC2 al desplegar. Se leen
# de alli para no guardarlas en el portatil.
$previo = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$credenciales = & ssh @opcionesSsh -o BatchMode=yes "ec2-user@$Ip" "sudo grep -E '^RABBITMQ_(USER|PASS)=' /opt/pedidos360-ep2/despliegue/.env" 2>$null
$codigo = $LASTEXITCODE
$ErrorActionPreference = $previo

if ($codigo -ne 0 -or -not $credenciales) {
    Write-Host ""
    Write-Host "No se pudo entrar a la EC2 por SSH." -ForegroundColor Red
    Write-Host "Lo mas probable: tu IP publica cambio y el Security Group solo admite la anterior." -ForegroundColor DarkGray
    Write-Host "Con el lab iniciado y las credenciales cargadas, .\07-desplegar-ep2.ps1 la actualiza." -ForegroundColor DarkGray
    exit 1
}
$usuario = (($credenciales | Where-Object { $_ -like "RABBITMQ_USER=*" }) -split "=", 2)[1]
$clave   = (($credenciales | Where-Object { $_ -like "RABBITMQ_PASS=*" }) -split "=", 2)[1]

$reenvios = @()
foreach ($p in $puertos.Keys) { $reenvios += @("-L", "${p}:127.0.0.1:$p") }

Write-Host ""
Write-Host "=== Tunel a la EC2 ($Ip) ===" -ForegroundColor Cyan
foreach ($p in $puertos.Keys) {
    Write-Host ("  localhost:{0,-6} {1}" -f $p, $puertos[$p]) -ForegroundColor Green
}
Write-Host ""
Write-Host "  RabbitMQ Management:  http://localhost:15672" -ForegroundColor Cyan
Write-Host "    usuario:    $usuario" -ForegroundColor Cyan
Write-Host "    contrasena: $clave" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Postman y pruebas\DEMOSTRACION.md funcionan igual que en local." -ForegroundColor DarkGray
Write-Host "  Logs en otra ventana:  .\08-tunel-demo.ps1 -Logs" -ForegroundColor DarkGray
Write-Host ""
Write-Host "Tunel abierto. Ctrl+C para cerrarlo." -ForegroundColor Yellow

$ErrorActionPreference = "Continue"
& ssh @opcionesSsh -N @reenvios "ec2-user@$Ip"
