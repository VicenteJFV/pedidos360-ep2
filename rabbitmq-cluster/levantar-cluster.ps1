<#
.SINOPSIS
    Levanta los dos nodos RabbitMQ y forma el cluster.

.DESCRIPCION
    Cubre las Etapas 1 y 2 de la guia de cluster: arranca los contenedores,
    espera a que ambos nodos respondan y une rabbitmq-2 al cluster de
    rabbitmq-1. Es idempotente: si el cluster ya esta formado, lo informa y no
    vuelve a unirlo.

    Ojo con el puerto 5672: si tienes corriendo el contenedor de la actividad
    anterior (rabbitmq-resiliencia), hay que detenerlo antes.

        docker stop rabbitmq-resiliencia

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

try {
    if (-not (docker info 2>$null)) {
        throw "Docker no responde. Abre Docker Desktop y vuelve a intentar."
    }

    # El puerto 5672 solo lo puede publicar un contenedor a la vez.
    $enConflicto = docker ps --filter "publish=5672" --format "{{.Names}}" |
                   Where-Object { $_ -and $_ -ne "rabbitmq-1" }
    if ($enConflicto) {
        Write-Host "  Deteniendo '$enConflicto', que ocupa el puerto 5672..." -ForegroundColor Yellow
        docker stop $enConflicto | Out-Null
        Write-Host "  Para reactivarlo despues: docker start $enConflicto" -ForegroundColor DarkGray
    }

    if ($Recrear) {
        Write-Host "[0/3] Eliminando contenedores y volumenes..." -ForegroundColor Cyan
        docker compose down -v 2>$null | Out-Null
    }

    Write-Host "[1/3] Levantando los nodos..." -ForegroundColor Cyan
    docker compose up -d 2>&1 | Out-Null

    Write-Host "[2/3] Esperando a que ambos nodos respondan..." -ForegroundColor Cyan
    $listos = $false
    for ($i = 1; $i -le ($SegundosEspera / 5); $i++) {
        Start-Sleep -Seconds 5
        docker exec rabbitmq-1 rabbitmq-diagnostics -q ping 2>$null | Out-Null
        $n1 = $LASTEXITCODE -eq 0
        docker exec rabbitmq-2 rabbitmq-diagnostics -q ping 2>$null | Out-Null
        $n2 = $LASTEXITCODE -eq 0
        if ($n1 -and $n2) { $listos = $true; break }
    }
    if (-not $listos) { throw "Los nodos no respondieron en $SegundosEspera segundos." }

    # ---------------------------------------------------------------------
    # Formacion del cluster
    # ---------------------------------------------------------------------
    $estado = docker exec rabbitmq-1 rabbitmqctl cluster_status 2>$null | Out-String

    if ($estado -match "rabbit@rabbitmq-2") {
        Write-Host "[3/3] El cluster ya estaba formado." -ForegroundColor Yellow
    } else {
        Write-Host "[3/3] Uniendo rabbitmq-2 al cluster de rabbitmq-1..." -ForegroundColor Cyan
        docker exec rabbitmq-2 rabbitmqctl stop_app  2>&1 | Out-Null
        docker exec rabbitmq-2 rabbitmqctl join_cluster rabbit@rabbitmq-1 2>&1 | Out-Null
        docker exec rabbitmq-2 rabbitmqctl start_app 2>&1 | Out-Null
        Start-Sleep -Seconds 8
    }

    Write-Host ""
    Write-Host "=== cluster_status ===" -ForegroundColor Cyan
    docker exec rabbitmq-1 rabbitmqctl cluster_status 2>$null |
        Select-String -Pattern "Disk Nodes" -Context 0, 4

    Write-Host ""
    Write-Host "  Management nodo 1: http://localhost:15672   (guest / guest)" -ForegroundColor Green
    Write-Host "  Management nodo 2: http://localhost:15673   (guest / guest)" -ForegroundColor Green
    Write-Host "  AMQP: localhost:5672 y localhost:5673" -ForegroundColor Green
    Write-Host ""
}
finally {
    Pop-Location
}
