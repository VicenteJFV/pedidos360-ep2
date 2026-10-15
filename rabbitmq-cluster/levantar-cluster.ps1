<#
.SINOPSIS
    Levanta los dos nodos RabbitMQ y forma el cluster.

.DESCRIPCION
    Cubre las Etapas 1 y 2 de la guia de cluster: arranca los contenedores,
    espera a que ambos nodos respondan y une rabbitmq-2 al cluster de
    rabbitmq-1. Es idempotente: si el cluster ya esta formado, lo informa y no
    vuelve a unirlo. Sirve igual para el primer arranque que para recuperar los
    contenedores despues de cerrar Docker Desktop.

    Ojo con el puerto 5672: si tienes corriendo el contenedor de la actividad
    anterior (rabbitmq-resiliencia), el script lo detiene y te dice como
    reactivarlo.

.EJEMPLO
    .\levantar-cluster.ps1

.EJEMPLO
    # Borra volumenes y vuelve a empezar de cero
    .\levantar-cluster.ps1 -Recrear
#>
[CmdletBinding()]
param(
    [switch]$Recrear,
    [int]$SegundosEspera = 120
)

$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot

<#
    Ejecuta un comando nativo sin que su stderr mate el script.

    docker escribe en stderr lineas perfectamente normales ("Container
    rabbitmq-1 Starting") y PowerShell 5.1, con ErrorActionPreference en Stop,
    las envuelve en un ErrorRecord y aborta aunque el comando haya salido con
    codigo 0. Por eso aqui se baja el preference solo durante la llamada y se
    decide por el codigo de salida, que es el unico dato fiable.

    Devuelve la salida combinada; el codigo queda en $script:ultimoCodigo.
#>
function Invoke-Nativo {
    param([Parameter(Mandatory)][string[]]$Argumentos)

    $previo = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $resto = @()
        if ($Argumentos.Count -gt 1) { $resto = $Argumentos[1..($Argumentos.Count - 1)] }
        $salida = & $Argumentos[0] @resto 2>&1 | Out-String
        $script:ultimoCodigo = $LASTEXITCODE
        return $salida
    } finally {
        $ErrorActionPreference = $previo
    }
}

try {
    Invoke-Nativo @("docker", "info") | Out-Null
    if ($script:ultimoCodigo -ne 0) {
        throw "Docker no responde. Abre Docker Desktop, espera a que termine de arrancar y vuelve a intentar."
    }

    # El puerto 5672 solo lo puede publicar un contenedor a la vez.
    $enConflicto = (Invoke-Nativo @("docker", "ps", "--filter", "publish=5672", "--format", "{{.Names}}")) -split "`r?`n" |
                   ForEach-Object { $_.Trim() } |
                   Where-Object { $_ -and $_ -ne "rabbitmq-1" }
    foreach ($nombre in $enConflicto) {
        Write-Host "  Deteniendo '$nombre', que ocupa el puerto 5672..." -ForegroundColor Yellow
        Invoke-Nativo @("docker", "stop", $nombre) | Out-Null
        Write-Host "  Para reactivarlo despues: docker start $nombre" -ForegroundColor DarkGray
    }

    if ($Recrear) {
        Write-Host "[0/3] Eliminando contenedores y volumenes..." -ForegroundColor Cyan
        Invoke-Nativo @("docker", "compose", "down", "-v") | Out-Null
    }

    Write-Host "[1/3] Levantando los nodos..." -ForegroundColor Cyan
    $salida = Invoke-Nativo @("docker", "compose", "up", "-d")
    if ($script:ultimoCodigo -ne 0) {
        Write-Host $salida -ForegroundColor DarkGray
        throw "docker compose up fallo."
    }

    Write-Host "[2/3] Esperando a que ambos nodos respondan..." -ForegroundColor Cyan
    $listos = $false
    for ($i = 1; $i -le ($SegundosEspera / 5); $i++) {
        Start-Sleep -Seconds 5

        Invoke-Nativo @("docker", "exec", "rabbitmq-1", "rabbitmq-diagnostics", "-q", "ping") | Out-Null
        $n1 = $script:ultimoCodigo -eq 0
        Invoke-Nativo @("docker", "exec", "rabbitmq-2", "rabbitmq-diagnostics", "-q", "ping") | Out-Null
        $n2 = $script:ultimoCodigo -eq 0

        if ($n1 -and $n2) { $listos = $true; break }
    }
    if (-not $listos) { throw "Los nodos no respondieron en $SegundosEspera segundos." }

    # ---------------------------------------------------------------------
    # Formacion del cluster
    # ---------------------------------------------------------------------
    # Al reiniciar contenedores ya clusterizados, la membresia sobrevive en el
    # volumen y no hay que volver a unirlos. Por eso se consulta primero.
    $estado = Invoke-Nativo @("docker", "exec", "rabbitmq-1", "rabbitmqctl", "cluster_status")

    if ($estado -match "rabbit@rabbitmq-2") {
        Write-Host "[3/3] El cluster ya estaba formado." -ForegroundColor Yellow
    } else {
        Write-Host "[3/3] Uniendo rabbitmq-2 al cluster de rabbitmq-1..." -ForegroundColor Cyan
        Invoke-Nativo @("docker", "exec", "rabbitmq-2", "rabbitmqctl", "stop_app") | Out-Null
        Invoke-Nativo @("docker", "exec", "rabbitmq-2", "rabbitmqctl", "join_cluster", "rabbit@rabbitmq-1") | Out-Null
        if ($script:ultimoCodigo -ne 0) {
            throw "join_cluster fallo. Si el error menciona noconnection, los nodos tienen cookies distintas: vuelve a correr con -Recrear"
        }
        Invoke-Nativo @("docker", "exec", "rabbitmq-2", "rabbitmqctl", "start_app") | Out-Null
        Start-Sleep -Seconds 8
        $estado = Invoke-Nativo @("docker", "exec", "rabbitmq-1", "rabbitmqctl", "cluster_status")
    }

    Write-Host ""
    Write-Host "=== cluster_status ===" -ForegroundColor Cyan
    ($estado -split "`r?`n") |
        Select-String -Pattern "Disk Nodes" -Context 0, 4 |
        ForEach-Object { Write-Host $_ }

    $nodos = [regex]::Matches($estado, "rabbit@rabbitmq-\d") |
             ForEach-Object { $_.Value } |
             Sort-Object -Unique
    if (@($nodos).Count -eq 2) {
        Write-Host "  Los dos nodos estan en el cluster." -ForegroundColor Green
    } else {
        Write-Host "  Atencion: se esperaban 2 nodos y se encontraron $(@($nodos).Count)." -ForegroundColor Red
    }

    Write-Host ""
    Write-Host "  Management nodo 1: http://localhost:15672   (guest / guest)" -ForegroundColor Green
    Write-Host "  Management nodo 2: http://localhost:15673   (guest / guest)" -ForegroundColor Green
    Write-Host "  AMQP: localhost:5672 y localhost:5673" -ForegroundColor Green
    Write-Host ""
}
finally {
    Pop-Location
}
