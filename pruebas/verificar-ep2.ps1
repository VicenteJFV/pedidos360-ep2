<#
.SINOPSIS
    Prueba completa de la mensajeria de EP2: flujo normal, flujo con error que
    termina en DLQ, API de administracion y metricas.

.DESCRIPCION
    Es la "prueba final" de la Etapa 15 de la guia, automatizada. Arranca los
    cuatro microservicios contra el cluster RabbitMQ, ejecuta las pruebas
    obligatorias de la seccion 12 de la guia de EP2 y apaga todo al terminar.

    Requiere que el cluster este levantado:

        cd ..\rabbitmq-cluster ; .\levantar-cluster.ps1

    Y que los cuatro JAR esten compilados:

        .\verificar-ep2.ps1 -Compilar

    Los logs de los servicios quedan en ..\logs\ . El de notify es el que hay
    que mostrar en la presentacion: ahi aparecen los ACK, los reintentos y los
    NACK con su eventId.

.EJEMPLO
    .\verificar-ep2.ps1

.EJEMPLO
    .\verificar-ep2.ps1 -Compilar
#>
[CmdletBinding()]
param(
    [string]$RaizProyecto = (Split-Path $PSScriptRoot -Parent),
    [switch]$Compilar,
    [int]$SegundosEspera = 90
)

$ErrorActionPreference = "Stop"
$script:ok = 0
$script:fallos = @()

$urlCat   = "http://127.0.0.1:8082"
$urlPed   = "http://127.0.0.1:8081"
$urlNoti  = "http://127.0.0.1:8083"
$urlAdmin = "http://127.0.0.1:8084"
$urlMgmt  = "http://localhost:15672/api"

# ---------------------------------------------------------------------------
# Utilidades
# ---------------------------------------------------------------------------

function Invoke-Api {
    param([string]$Metodo, [string]$Url, $Cuerpo)

    $params = @{ Method = $Metodo; Uri = $Url; UseBasicParsing = $true; TimeoutSec = 30 }
    if ($null -ne $Cuerpo) {
        $params.Body = ($Cuerpo | ConvertTo-Json -Depth 6)
        $params.ContentType = "application/json; charset=utf-8"
    }

    try {
        $r = Invoke-WebRequest @params
        $json = $null
        if ($r.Content) { try { $json = $r.Content | ConvertFrom-Json } catch { } }
        return @{ Status = [int]$r.StatusCode; Json = $json }
    } catch {
        $resp = $_.Exception.Response
        if (-not $resp) { return @{ Status = 0; Json = $null } }
        # En PowerShell 5.1 el cuerpo del error NO esta en el stream, que ya fue
        # consumido: esta en ErrorDetails.Message. Leerlo del stream devuelve
        # vacio y hace creer que el servicio no responde nada.
        $json = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            try { $json = $_.ErrorDetails.Message | ConvertFrom-Json } catch { }
        }
        return @{ Status = [int]$resp.StatusCode; Json = $json }
    }
}

function Invoke-Management {
    param([string]$Ruta)
    $cred = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("guest:guest"))
    return Invoke-RestMethod -Uri "$urlMgmt$Ruta" -Headers @{ Authorization = "Basic $cred" } -TimeoutSec 20
}

function Comprobar {
    param([string]$Nombre, [bool]$Condicion, [string]$Detalle = "")
    if ($Condicion) {
        Write-Host ("  [OK]    " + $Nombre) -ForegroundColor Green
        $script:ok++
    } else {
        Write-Host ("  [FALLA] " + $Nombre) -ForegroundColor Red
        if ($Detalle) { Write-Host ("          " + $Detalle) -ForegroundColor DarkGray }
        $script:fallos += $Nombre
    }
}

function Obtener-Cola {
    param([string]$Nombre)
    $r = Invoke-Api GET "$urlAdmin/api/rabbitmq/queues/$Nombre"
    if ($r.Status -ne 200) { return $null }
    return $r.Json
}

function Mensajes-En {
    param([string]$Nombre)
    $info = Obtener-Cola $Nombre
    if ($null -eq $info) { return -1 }
    return [int]$info.mensajes
}

<#
    Espera a que una cola alcance una cantidad de mensajes. Es necesario porque
    el consumo es asincrono: preguntar de inmediato despues de aceptar un pedido
    da lecturas falsas, y el flujo transitorio tarda sus 7 segundos de backoff.
#>
function Esperar-Mensajes {
    param([string]$Cola, [int]$Esperados, [int]$Segundos = 30)
    for ($i = 0; $i -lt $Segundos; $i++) {
        if ((Mensajes-En $Cola) -ge $Esperados) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Crear-Pedido {
    param([string]$Correo, [int]$ProductoId, [int]$Cantidad = 1)
    return Invoke-Api POST "$urlPed/api/orders" @{
        clienteEmail     = $Correo
        clienteNombre    = "Prueba EP2"
        direccionEntrega = "Av. Siempre Viva 742"
        items            = @(@{ productoId = $ProductoId; cantidad = $Cantidad })
    }
}

# ---------------------------------------------------------------------------
# Compilacion y arranque
# ---------------------------------------------------------------------------

$modulos = @(
    @{ Nombre = "ms-pedidos360-catalog";     Puerto = 8082; Url = $urlCat },
    @{ Nombre = "ms-pedidos360-orders";      Puerto = 8081; Url = $urlPed },
    @{ Nombre = "ms-pedidos360-notify";      Puerto = 8083; Url = $urlNoti },
    @{ Nombre = "ms-pedidos360-rabbitadmin"; Puerto = 8084; Url = $urlAdmin }
)

if ($Compilar) {
    Write-Host ""
    Write-Host "=== Compilando los cuatro microservicios ===" -ForegroundColor Cyan
    foreach ($m in $modulos) {
        Write-Host "  $($m.Nombre)..." -ForegroundColor DarkGray
        Push-Location (Join-Path $RaizProyecto $m.Nombre)
        try {
            mvn -B -ntp -DskipTests clean package | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "La compilacion de $($m.Nombre) fallo" }
        } finally { Pop-Location }
    }
}

foreach ($m in $modulos) {
    $jar = Join-Path $RaizProyecto "$($m.Nombre)\target\$($m.Nombre).jar"
    if (-not (Test-Path $jar)) {
        throw "Falta $jar . Vuelve a ejecutar con -Compilar"
    }
    $m.Jar = $jar
}

# El cluster tiene que estar arriba antes que los servicios.
try {
    $nodos = Invoke-Management "/nodes"
} catch {
    throw "RabbitMQ Management no responde en localhost:15672. Levanta el cluster: cd ..\rabbitmq-cluster ; .\levantar-cluster.ps1"
}

$carpetaLogs = Join-Path $RaizProyecto "logs"
if (-not (Test-Path $carpetaLogs)) { New-Item -ItemType Directory -Path $carpetaLogs | Out-Null }

$procesos = @()

try {
    Write-Host ""
    Write-Host "=== Cluster RabbitMQ ===" -ForegroundColor Cyan

    Comprobar "El cluster tiene dos nodos" ($nodos.Count -eq 2) "nodos=$($nodos.Count)"
    Comprobar "Los dos nodos estan corriendo" (($nodos | Where-Object { $_.running }).Count -eq 2)
    Write-Host "  ($(($nodos | ForEach-Object { $_.name }) -join ', '))" -ForegroundColor DarkGray

    Write-Host ""
    Write-Host "=== Arrancando microservicios ===" -ForegroundColor Cyan

    foreach ($m in $modulos) {
        $corto = $m.Nombre.Replace("ms-pedidos360-", "")
        $procesos += Start-Process -FilePath "java" -ArgumentList "-jar `"$($m.Jar)`"" `
            -RedirectStandardOutput "$carpetaLogs\$corto.log" `
            -RedirectStandardError "$carpetaLogs\$corto.err" `
            -WindowStyle Hidden -PassThru
    }

    $arriba = $false
    for ($i = 1; $i -le $SegundosEspera; $i++) {
        Start-Sleep -Seconds 1
        $vivos = 0
        foreach ($m in $modulos) {
            if ((Invoke-Api GET "$($m.Url)/actuator/health").Status -eq 200) { $vivos++ }
        }
        if ($vivos -eq $modulos.Count) { $arriba = $true; break }
    }

    if (-not $arriba) {
        Write-Host "Los servicios no respondieron en $SegundosEspera segundos." -ForegroundColor Red
        foreach ($m in $modulos) {
            $corto = $m.Nombre.Replace("ms-pedidos360-", "")
            Write-Host "--- $corto.log ---" -ForegroundColor DarkGray
            Get-Content "$carpetaLogs\$corto.log" -Tail 20 -ErrorAction SilentlyContinue
        }
        throw "Arranque fallido"
    }
    Write-Host "  los cuatro servicios responden (tras $i s)" -ForegroundColor Green

    # -----------------------------------------------------------------------
    # Topologia
    # -----------------------------------------------------------------------

    Write-Host ""
    Write-Host "=== Topologia del caso ===" -ForegroundColor Cyan

    $exchanges = Invoke-Management "/exchanges/%2F"
    foreach ($ex in @(
        @{ n = "cmd.direct";   t = "direct" },
        @{ n = "cmd.topic";    t = "topic"  },
        @{ n = "cmd.dead.dlx"; t = "direct" })) {
        $encontrado = $exchanges | Where-Object { $_.name -eq $ex.n }
        Comprobar "Existe el exchange $($ex.n) de tipo $($ex.t)" `
            ($null -ne $encontrado -and $encontrado.type -eq $ex.t) "tipo=$($encontrado.type)"
    }

    $colasFuncionales = @("q.cmd.email", "q.cmd.kitchen", "q.cmd.invoice")
    $colasMuertas     = @("q.cmd.email.dlq", "q.cmd.kitchen.dlq", "q.cmd.invoice.dlq")

    foreach ($c in ($colasFuncionales + $colasMuertas)) {
        Comprobar "Existe la cola $c" ($null -ne (Obtener-Cola $c))
    }

    # Retencion: la guia pide demostrar que la politica esta aplicada.
    $detalleColas = Invoke-Management "/queues/%2F"
    foreach ($c in $colasFuncionales) {
        $cola = $detalleColas | Where-Object { $_.name -eq $c }
        # Ojo: no usar $args, que es una variable automatica de PowerShell.
        $argumentos = $cola.arguments
        Comprobar "$c tiene TTL y tope de mensajes" `
            ($null -ne $argumentos.'x-message-ttl' -and $null -ne $argumentos.'x-max-length') `
            "ttl=$($argumentos.'x-message-ttl') max=$($argumentos.'x-max-length')"
        Comprobar "$c apunta a su DLQ via cmd.dead.dlx" `
            ($argumentos.'x-dead-letter-exchange' -eq "cmd.dead.dlx")
    }

    $metricas = (Invoke-Api GET "$urlAdmin/api/rabbitmq/metricas").Json
    Comprobar "Las seis colas del caso estan activas" ($metricas.colasActivas -eq 6) `
        "activas=$($metricas.colasActivas)"
    Comprobar "Hay un consumidor por cola funcional" ($metricas.consumidores -eq 3) `
        "consumidores=$($metricas.consumidores)"

    # Se parte de DLQ vacias para que los deltas signifiquen algo. Purgar es,
    # ademas, una de las operaciones que la guia pide demostrar.
    foreach ($c in $colasMuertas) {
        $p = Invoke-Api POST "$urlAdmin/api/rabbitmq/queues/$c/purge"
        Comprobar "Purgar $c responde 200" ($p.Status -eq 200) "status=$($p.Status)"
    }

    # -----------------------------------------------------------------------
    # Flujo normal
    # -----------------------------------------------------------------------

    Write-Host ""
    Write-Host "=== Flujo normal: productor, consumidor y ACK ===" -ForegroundColor Cyan

    $catalogo = (Invoke-Api GET "$urlCat/api/catalog").Json
    $producto = $catalogo | Where-Object { $_.sku -eq "SKU-001" } | Select-Object -First 1
    if ($null -eq $producto) { throw "El catalogo no tiene SKU-001; revisa los datos iniciales" }

    $pedido = Crear-Pedido "cliente@duoc.cl" $producto.id 1
    Comprobar "POST /api/orders crea el pedido" ($pedido.Status -eq 201) "status=$($pedido.Status)"
    $idOk = $pedido.Json.id

    $aceptado = Invoke-Api PUT "$urlPed/api/orders/$idOk/accept"
    Comprobar "Aceptar el pedido responde 200" ($aceptado.Status -eq 200) "status=$($aceptado.Status)"

    Start-Sleep -Seconds 4
    Comprobar "q.cmd.email quedo vacia: el consumidor confirmo el mensaje" `
        ((Mensajes-En "q.cmd.email") -eq 0) "mensajes=$(Mensajes-En 'q.cmd.email')"
    Comprobar "q.cmd.kitchen quedo vacia" `
        ((Mensajes-En "q.cmd.kitchen") -eq 0) "mensajes=$(Mensajes-En 'q.cmd.kitchen')"
    Comprobar "Ninguna DLQ recibio nada en el flujo normal" `
        ((Mensajes-En "q.cmd.email.dlq") -eq 0 -and (Mensajes-En "q.cmd.kitchen.dlq") -eq 0)

    # Las cuatro lineas que la guia de prueba empirica pide mostrar en pantalla.
    $logNotify = Get-Content "$carpetaLogs\notify.log" -Raw -ErrorAction SilentlyContinue
    Comprobar "El log de notify nombra la Queue y la Routing Key del mensaje" `
        ($logNotify -match "MENSAJE RECIBIDO \| Queue: q\.cmd\.email \| Routing Key: email\.send")
    Comprobar "El log de notify muestra el procesamiento exitoso" `
        ($logNotify -match "PROCESAMIENTO EXITOSO")
    Comprobar "El log de notify muestra el ACK manual" `
        ($logNotify -match "ACK MANUAL ENVIADO")
    Comprobar "El log de notify muestra el correo procesado" `
        ($logNotify -match "CORREO ENVIADO")
    Comprobar "El log de notify muestra el ticket de cocina" `
        ($logNotify -match "TICKET DE COCINA")

    # Completar el ciclo: despachar vuelve a notificar, entregar pide el documento.
    Invoke-Api PUT "$urlPed/api/orders/$idOk/prepare"  | Out-Null
    Invoke-Api PUT "$urlPed/api/orders/$idOk/ship"     | Out-Null
    $entregado = Invoke-Api PUT "$urlPed/api/orders/$idOk/deliver"
    Comprobar "El pedido llega a ENTREGADO" `
        ($entregado.Status -eq 200 -and $entregado.Json.estado -eq "ENTREGADO") `
        "estado=$($entregado.Json.estado)"

    Start-Sleep -Seconds 4
    $logNotify = Get-Content "$carpetaLogs\notify.log" -Raw -ErrorAction SilentlyContinue
    Comprobar "El log de notify muestra el documento generado" `
        ($logNotify -match "DOCUMENTO GENERADO")

    $logOrders = Get-Content "$carpetaLogs\orders.log" -Raw -ErrorAction SilentlyContinue
    Comprobar "El log del productor nombra Exchange, Routing Key, eventId y correlationId" `
        ($logOrders -match "Publicando mensaje \| Exchange: cmd\.direct \| Routing Key: email\.send \| eventId: .+ \| correlationId: ")
    Comprobar "El productor publico tambien por cmd.topic" `
        ($logOrders -match "Exchange: cmd\.topic \| Routing Key: kitchen\.ticket\.new")
    Comprobar "q.cmd.invoice quedo vacia" ((Mensajes-En "q.cmd.invoice") -eq 0)

    # -----------------------------------------------------------------------
    # Flujo con error controlado
    # -----------------------------------------------------------------------

    Write-Host ""
    Write-Host "=== Error controlado: NACK y DLQ ===" -ForegroundColor Cyan
    Write-Host "  (el disparador es el correo del pedido, no un cambio de codigo)" -ForegroundColor DarkGray

    $pedidoPerm = Crear-Pedido "error-permanente@pedidos360.cl" $producto.id 1
    Comprobar "Se crea el pedido con el correo que provoca la falla definitiva" `
        ($pedidoPerm.Status -eq 201) "status=$($pedidoPerm.Status)"
    Invoke-Api PUT "$urlPed/api/orders/$($pedidoPerm.Json.id)/accept" | Out-Null

    Comprobar "El mensaje de email termina en q.cmd.email.dlq" `
        (Esperar-Mensajes "q.cmd.email.dlq" 1 20) "mensajes=$(Mensajes-En 'q.cmd.email.dlq')"
    Comprobar "El mensaje de cocina termina en q.cmd.kitchen.dlq" `
        (Esperar-Mensajes "q.cmd.kitchen.dlq" 1 20) "mensajes=$(Mensajes-En 'q.cmd.kitchen.dlq')"
    Comprobar "La cola principal quedo vacia: el mensaje no se reencolo en bucle" `
        ((Mensajes-En "q.cmd.email") -eq 0)

    $logNotify = Get-Content "$carpetaLogs\notify.log" -Raw -ErrorAction SilentlyContinue
    Comprobar "El log muestra el error no recuperable" ($logNotify -match "ERROR NO RECUPERABLE")
    Comprobar "El log muestra el NACK sin requeue" ($logNotify -match "NACK SIN REQUEUE ENVIADO")

    # Falla transitoria: cuatro intentos con backoff 1s, 2s, 4s y recien ahi la DLQ.
    $pedidoTrans = Crear-Pedido "error-transitorio@pedidos360.cl" $producto.id 1
    Invoke-Api PUT "$urlPed/api/orders/$($pedidoTrans.Json.id)/accept" | Out-Null

    Comprobar "El mensaje transitorio termina en la DLQ tras agotar reintentos" `
        (Esperar-Mensajes "q.cmd.email.dlq" 2 40) "mensajes=$(Mensajes-En 'q.cmd.email.dlq')"

    $logNotify = Get-Content "$carpetaLogs\notify.log" -Raw -ErrorAction SilentlyContinue
    Comprobar "El log muestra los reintentos con su backoff" `
        ($logNotify -match "reintento en 1000 ms" -and $logNotify -match "reintento en 4000 ms")
    Comprobar "El log muestra que los reintentos se agotaron" `
        ($logNotify -match "REINTENTOS AGOTADOS")
    Comprobar "Hubo cuatro intentos: el inicial mas tres" `
        ($logNotify -match "intento 3 de 4")

    $metricas = (Invoke-Api GET "$urlAdmin/api/rabbitmq/metricas").Json
    Comprobar "Las metricas reportan los mensajes en DLQ" ($metricas.mensajesEnDlq -ge 3) `
        "enDlq=$($metricas.mensajesEnDlq)"
    Comprobar "La tasa de DLQ es un valor entre 0 y 1" `
        ($metricas.tasaDlq -ge 0 -and $metricas.tasaDlq -le 1) "tasa=$($metricas.tasaDlq)"

    # -----------------------------------------------------------------------
    # API de administracion
    # -----------------------------------------------------------------------

    Write-Host ""
    Write-Host "=== Administracion de recursos ===" -ForegroundColor Cyan

    $colaTmp = "q.prueba.ep2"
    $exTmp   = "ex.prueba.ep2"
    $rkTmp   = "prueba.ep2.rk"

    $c = Invoke-Api POST "$urlAdmin/api/rabbitmq/queues?queueName=$colaTmp"
    Comprobar "Crear cola valida responde 201" ($c.Status -eq 201) "status=$($c.Status)"

    $g = Invoke-Api GET "$urlAdmin/api/rabbitmq/queues/$colaTmp"
    Comprobar "Consultar la cola responde 200 con sus metricas" `
        ($g.Status -eq 200 -and $g.Json.nombre -eq $colaTmp) "status=$($g.Status)"

    $e = Invoke-Api POST "$urlAdmin/api/rabbitmq/exchanges?exchangeName=$exTmp&type=topic"
    Comprobar "Crear exchange valido responde 201" ($e.Status -eq 201) "status=$($e.Status)"

    $b = Invoke-Api POST "$urlAdmin/api/rabbitmq/bindings?queueName=$colaTmp&exchangeName=$exTmp&routingKey=$rkTmp"
    Comprobar "Crear binding con los tres parametros responde 201" ($b.Status -eq 201) "status=$($b.Status)"

    $bd = Invoke-Api DELETE "$urlAdmin/api/rabbitmq/bindings?queueName=$colaTmp&exchangeName=$exTmp&routingKey=$rkTmp"
    Comprobar "Eliminar el binding responde 200" ($bd.Status -eq 200) "status=$($bd.Status)"

    $pd = Invoke-Api POST "$urlAdmin/api/rabbitmq/queues/$colaTmp/purge"
    Comprobar "Purgar la cola responde 200" ($pd.Status -eq 200) "status=$($pd.Status)"

    $cd = Invoke-Api DELETE "$urlAdmin/api/rabbitmq/queues/$colaTmp"
    Comprobar "Eliminar la cola responde 200" ($cd.Status -eq 200) "status=$($cd.Status)"

    $gd = Invoke-Api GET "$urlAdmin/api/rabbitmq/queues/$colaTmp"
    Comprobar "La cola eliminada ya no existe: GET responde 404" ($gd.Status -eq 404) "status=$($gd.Status)"

    $ed = Invoke-Api DELETE "$urlAdmin/api/rabbitmq/exchanges/$exTmp"
    Comprobar "Eliminar el exchange responde 200" ($ed.Status -eq 200) "status=$($ed.Status)"

    # -----------------------------------------------------------------------
    # Validacion: HTTP 400
    # -----------------------------------------------------------------------

    Write-Host ""
    Write-Host "=== Validacion de parametros ===" -ForegroundColor Cyan

    $v1 = Invoke-Api POST "$urlAdmin/api/rabbitmq/queues"
    Comprobar "Cola sin nombre responde 400" ($v1.Status -eq 400) "status=$($v1.Status)"
    Comprobar "El 400 nombra el parametro que falta" ($v1.Json.parametro -eq "queueName") `
        "parametro=$($v1.Json.parametro)"

    $v2 = Invoke-Api POST "$urlAdmin/api/rabbitmq/queues?queueName=%20%20"
    Comprobar "Cola con nombre en blanco responde 400" ($v2.Status -eq 400) "status=$($v2.Status)"

    $v3 = Invoke-Api POST "$urlAdmin/api/rabbitmq/exchanges?exchangeName=ex.x"
    Comprobar "Exchange sin tipo responde 400" ($v3.Status -eq 400) "status=$($v3.Status)"

    $v4 = Invoke-Api POST "$urlAdmin/api/rabbitmq/exchanges?exchangeName=ex.x&type=directo"
    Comprobar "Exchange con tipo invalido responde 400" ($v4.Status -eq 400) "status=$($v4.Status)"

    $v5 = Invoke-Api POST "$urlAdmin/api/rabbitmq/bindings?queueName=q.x&routingKey=r.x"
    Comprobar "Binding sin exchange responde 400" ($v5.Status -eq 400) "status=$($v5.Status)"
    Comprobar "El 400 del binding nombra exchangeName" ($v5.Json.parametro -eq "exchangeName") `
        "parametro=$($v5.Json.parametro)"

    $v6 = Invoke-Api POST "$urlAdmin/api/rabbitmq/bindings?queueName=q.x&exchangeName=cmd.direct"
    Comprobar "Binding sin routing key responde 400" ($v6.Status -eq 400) "status=$($v6.Status)"

    $v7 = Invoke-Api GET "$urlAdmin/api/rabbitmq/queues/q.no.existe.ep2"
    Comprobar "Consultar una cola inexistente responde 404, no 500" ($v7.Status -eq 404) `
        "status=$($v7.Status)"

    # -----------------------------------------------------------------------
    # EP1 sigue funcionando
    # -----------------------------------------------------------------------

    Write-Host ""
    Write-Host "=== El flujo de EP1 no se rompio ===" -ForegroundColor Cyan

    $sinStock = Crear-Pedido "cliente@duoc.cl" $producto.id 999999
    Comprobar "Pedir mas stock del disponible sigue devolviendo 409" ($sinStock.Status -eq 409) `
        "status=$($sinStock.Status)"

    $pedidoMal = Crear-Pedido "cliente@duoc.cl" $producto.id 1
    $malaTransicion = Invoke-Api PUT "$urlPed/api/orders/$($pedidoMal.Json.id)/ship"
    Comprobar "Despachar sin aceptar sigue devolviendo 400" ($malaTransicion.Status -eq 400) `
        "status=$($malaTransicion.Status)"

    $noExiste = Invoke-Api GET "$urlPed/api/orders/999999"
    Comprobar "Pedido inexistente sigue devolviendo 404" ($noExiste.Status -eq 404) `
        "status=$($noExiste.Status)"

} finally {
    Write-Host ""
    Write-Host "=== Apagando microservicios ===" -ForegroundColor Cyan
    foreach ($p in $procesos) {
        if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    }
    Write-Host "  (el cluster sigue arriba; para apagarlo: cd ..\rabbitmq-cluster ; docker compose down)" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------------------
# Resumen
# ---------------------------------------------------------------------------

$total = $script:ok + $script:fallos.Count

Write-Host ""
Write-Host "=======================================" -ForegroundColor Cyan
Write-Host " $($script:ok) de $total comprobaciones correctas" -ForegroundColor Cyan
Write-Host "=======================================" -ForegroundColor Cyan

if ($script:fallos.Count -gt 0) {
    Write-Host ""
    Write-Host "Fallaron:" -ForegroundColor Red
    $script:fallos | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Host ""
    Write-Host "Los logs estan en $carpetaLogs" -ForegroundColor DarkGray
    exit 1
}

Write-Host ""
Write-Host "TODO CORRECTO" -ForegroundColor Green
Write-Host "Los logs de la demostracion quedaron en $carpetaLogs" -ForegroundColor DarkGray
Write-Host "El de notify es el que conviene mostrar: ACK, reintentos y NACK con su eventId." -ForegroundColor DarkGray
