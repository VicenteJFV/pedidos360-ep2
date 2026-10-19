<#
.SINOPSIS
    Ejecuta la matriz de pruebas de seguridad de la pauta contra el API Gateway
    real, con tokens reales de Microsoft Entra ID.

.DESCRIPCION
    Cubre los cinco escenarios de la seccion 6 de la pauta:

      | Escenario                | Peticion                  | Rol     | Espera |
      | Sin token                | GET  /api/orders          | -       | 401    |
      | Token invalido/expirado  | GET  /api/orders          | corrupto| 401    |
      | Acceso permitido         | GET  /api/orders          | Cliente | 200    |
      | Sin permiso de rol       | PUT  /api/orders/{id}/accept | Cliente | 403 |
      | Error de regla de negocio| PUT  /api/orders/{id}/ship   | Admin   | 400 |

    Pide dos inicios de sesion en el navegador: uno con la cuenta Cliente y
    otro con la cuenta Admin. Las contrasenas se escriben en la pagina de
    Microsoft, nunca en la consola ni en este script.

.EJEMPLO
    .\verificar-matriz.ps1
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "https://xpglku1pj5.execute-api.us-east-1.amazonaws.com/Desarrollo",
    [string]$OrigenCors = "http://localhost:4200",


    [string]$CuentaCliente = "Cliente@luissantacruz2026.onmicrosoft.com",
    [string]$CuentaAdmin   = "Admin@luissantacruz2026.onmicrosoft.com",

    # Permite reutilizar tokens ya obtenidos y saltarse los logins.
    [string]$TokenCliente,
    [string]$TokenAdmin
)

$ErrorActionPreference = "Stop"
$script:ok = 0
$script:fallos = @()

function Invoke-Api {
    param([string]$Metodo, [string]$Ruta, [string]$Token, [hashtable]$Encabezados, $Cuerpo)

    $h = @{}
    if ($Encabezados) { $h = $Encabezados.Clone() }
    if ($Token) { $h["Authorization"] = "Bearer $Token" }

    $params = @{
        Method          = $Metodo
        Uri             = "$BaseUrl$Ruta"
        Headers         = $h
        UseBasicParsing = $true
        TimeoutSec      = 30
    }
    if ($null -ne $Cuerpo) {
        $params.Body        = ($Cuerpo | ConvertTo-Json -Depth 6)
        $params.ContentType = "application/json; charset=utf-8"
    }

    try {
        $r = Invoke-WebRequest @params
        $json = $null
        if ($r.Content) { try { $json = $r.Content | ConvertFrom-Json } catch { } }
        return @{ Status = [int]$r.StatusCode; Json = $json; Headers = $r.Headers; Texto = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        if (-not $resp) { return @{ Status = 0; Json = $null; Texto = $_.Exception.Message } }

        # PowerShell 5.1 consume y cierra el stream de la respuesta antes de
        # lanzar la excepcion, asi que GetResponseStream() devuelve vacio y
        # parece que el servidor no envio cuerpo. El contenido real queda en
        # ErrorDetails.Message; el stream solo sirve como respaldo para
        # PowerShell 7, donde ErrorDetails a veces viene nulo.
        $texto = ""
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $texto = $_.ErrorDetails.Message
        } else {
            try {
                $flujo = $resp.GetResponseStream()
                if ($flujo -and $flujo.CanRead) {
                    $texto = (New-Object System.IO.StreamReader($flujo)).ReadToEnd()
                }
            } catch { }
        }
        $json = $null
        if ($texto) {
            try {
                $json = $texto | ConvertFrom-Json
                # Si el proxy serializo el JSON como una cadena en vez de
                # escribirlo crudo, el resultado es un string y hay que
                # interpretarlo una segunda vez.
                if ($json -is [string]) { $json = $json | ConvertFrom-Json }
            } catch { }
        }
        return @{ Status = [int]$resp.StatusCode; Json = $json; Headers = $resp.Headers; Texto = $texto }
    }
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

function Get-Claims {
    param([string]$Jwt)
    $p = $Jwt.Split('.')[1].Replace('-', '+').Replace('_', '/')
    switch ($p.Length % 4) { 2 { $p += '==' } 3 { $p += '=' } }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p)) | ConvertFrom-Json
}

# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "=== Matriz de pruebas de seguridad ===" -ForegroundColor Cyan
Write-Host "  Destino: $BaseUrl"
Write-Host ""

$obtener = Join-Path $PSScriptRoot "obtener-token.ps1"

if (-not $TokenCliente) {
    Write-Host "Inicia sesion con $CuentaCliente ..." -ForegroundColor Cyan
    $TokenCliente = & $obtener -SoloToken -Cuenta $CuentaCliente
}
if (-not $TokenAdmin) {
    Write-Host ""
    Write-Host "Ahora inicia sesion con $CuentaAdmin ..." -ForegroundColor Cyan
    Write-Host "Te va a pedir la contrasena: es a proposito, para forzar el cambio de cuenta." -ForegroundColor DarkGray
    $TokenAdmin = & $obtener -SoloToken -Cuenta $CuentaAdmin
}

# ---------------------------------------------------------------------------
# Los claims deciden si el resto de la matriz puede siquiera funcionar
# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "=== Claims de los tokens ===" -ForegroundColor Cyan

$cl = Get-Claims $TokenCliente
$ad = Get-Claims $TokenAdmin

foreach ($par in @(@{N = "Cliente"; C = $cl }, @{N = "Admin"; C = $ad })) {
    $c = $par.C
    Write-Host ("  {0,-8} upn={1}  roles={2}" -f $par.N, $c.preferred_username,
        $(if ($c.roles) { $c.roles -join "," } else { "(ninguno)" }))
}

# Si el navegador reutilizo la sesion, los dos tokens serian de la misma cuenta
# y todo lo que sigue daria 403 sin que eso signifique nada. Mejor cortar aqui.
if ($cl.preferred_username -eq $ad.preferred_username) {
    Write-Host ""
    Write-Host "  Los dos tokens son de la MISMA cuenta ($($cl.preferred_username))." -ForegroundColor Red
    Write-Host "  Entra ID reutilizo la sesion del navegador." -ForegroundColor Red
    Write-Host ""
    Write-Host "  Cierra sesion en https://login.microsoftonline.com/logout y reintenta," -ForegroundColor Yellow
    Write-Host "  o corre el script desde una ventana de incognito." -ForegroundColor Yellow
    Write-Host ""
    throw "No se pudo obtener un token por cada cuenta."
}

$issEsperado = "https://login.microsoftonline.com/17dd3345-54db-49ce-8172-092f2ccd50fd/v2.0"
Comprobar "El issuer es v2.0 (accessTokenAcceptedVersion = 2)" ($cl.iss -eq $issEsperado) "iss=$($cl.iss)"
Comprobar "La audiencia es el Client ID del backend" ($cl.aud -eq "fc8de29a-f24d-4173-bfc1-12580819e4c7") "aud=$($cl.aud)"
Comprobar "El token de Cliente trae el rol Cliente" ($cl.roles -contains "Cliente") "roles=$($cl.roles -join ',')"
Comprobar "El token de Admin trae el rol Admin" ($ad.roles -contains "Admin") "roles=$($ad.roles -join ',')"

# ---------------------------------------------------------------------------
# Los cinco escenarios de la pauta
# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "=== Escenario 1: sin token -> 401 ===" -ForegroundColor Cyan
$r = Invoke-Api GET "/api/orders"
Comprobar "API Gateway rechaza sin token" ($r.Status -eq 401) "status=$($r.Status)"

Write-Host ""
Write-Host "=== Escenario 2: token invalido -> 401 ===" -ForegroundColor Cyan
$r = Invoke-Api GET "/api/orders" -Token "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWxzbyJ9.firma_invalida"
Comprobar "API Gateway rechaza una firma invalida" ($r.Status -eq 401) "status=$($r.Status)"

Write-Host ""
Write-Host "=== Escenario 3: acceso permitido -> 200 ===" -ForegroundColor Cyan
$r = Invoke-Api GET "/api/orders" -Token $TokenCliente
Comprobar "Cliente puede listar pedidos" ($r.Status -eq 200) "status=$($r.Status)  $($r.Texto | Select-Object -First 1)"
Comprobar "La respuesta atraviesa hasta el microservicio" ($null -ne $r.Json) "cuerpo=$($r.Texto)"

$rc = Invoke-Api GET "/api/catalog" -Token $TokenCliente
Comprobar "Cliente puede ver el catalogo desde Oracle" ($rc.Status -eq 200 -and $rc.Json.Count -ge 1) "status=$($rc.Status) productos=$($rc.Json.Count)"

Write-Host ""
Write-Host "=== Escenario 4: rol sin permiso -> 403 ===" -ForegroundColor Cyan

# Se crea un pedido con el Admin para tener un id real sobre el que probar.
# Se usa el primer producto con stock: las demos y las pruebas anteriores van
# agotando los primeros SKU, y un 409 por stock no dice nada de la seguridad.
$producto = @($rc.Json | Where-Object { [int]$_.stock -ge 1 }) | Select-Object -First 1
if (-not $producto) { $producto = $rc.Json[0] }
$nuevo = Invoke-Api POST "/api/orders" -Token $TokenAdmin -Cuerpo @{
    clienteEmail = $ad.preferred_username
    items        = @(@{ productoId = $producto.id; cantidad = 1 })
}
if ($nuevo.Status -ne 201) {
    Comprobar "Se pudo crear un pedido de prueba" $false "status=$($nuevo.Status) $($nuevo.Texto)"
} else {
    $idPedido = $nuevo.Json.id
    Write-Host "  (pedido de prueba: $($nuevo.Json.codigo), id $idPedido)" -ForegroundColor DarkGray

    $r = Invoke-Api PUT "/api/orders/$idPedido/accept" -Token $TokenCliente
    Comprobar "El BFF rechaza a Cliente en /accept" ($r.Status -eq 403) "status=$($r.Status)"
    Comprobar "Es 403 y no 401: el token es valido, falla la autorizacion" ($r.Status -ne 401) "status=$($r.Status)"

    Write-Host ""
    Write-Host "=== Escenario 5: regla de negocio -> 400 ===" -ForegroundColor Cyan
    $r = Invoke-Api PUT "/api/orders/$idPedido/ship" -Token $TokenAdmin
    Comprobar "No se puede despachar sin aceptar" ($r.Status -eq 400) "status=$($r.Status)"

    # El cuerpo del error importa tanto como el codigo: es lo que el Angular
    # muestra al usuario.
    $cuerpoUtil = ($r.Json.mensaje -match "no puede pasar")
    Comprobar "El cuerpo del error llega intacto hasta el cliente" $cuerpoUtil `
        "longitud=$($r.Texto.Length)  crudo=[$($r.Texto)]"


    Write-Host ""
    Write-Host "=== Extra: flujo completo con Admin ===" -ForegroundColor Cyan
    $secuencia = @(
        @{ Accion = "accept";  Estado = "ACEPTADO" },
        @{ Accion = "prepare"; Estado = "EN_PREPARACION" },
        @{ Accion = "ship";    Estado = "DESPACHADO" },
        @{ Accion = "deliver"; Estado = "ENTREGADO" }
    )
    foreach ($paso in $secuencia) {
        $r = Invoke-Api PUT "/api/orders/$idPedido/$($paso.Accion)" -Token $TokenAdmin
        Comprobar "$($paso.Accion) -> $($paso.Estado)" ($r.Json.estado -eq $paso.Estado) "status=$($r.Status) estado=$($r.Json.estado)"
    }
}

Write-Host ""
Write-Host "=== Escenario 6: preflight CORS sin token -> 200 ===" -ForegroundColor Cyan
$r = Invoke-Api OPTIONS "/api/orders" -Encabezados @{
    "Origin"                         = $OrigenCors
    "Access-Control-Request-Method"  = "GET"
    "Access-Control-Request-Headers" = "authorization"
}
Comprobar "El preflight no exige token" ($r.Status -eq 200 -or $r.Status -eq 204) "status=$($r.Status)"
Comprobar "Devuelve Access-Control-Allow-Origin" ($null -ne $r.Headers["Access-Control-Allow-Origin"]) "origen=$($r.Headers['Access-Control-Allow-Origin'])"

# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "===================================" -ForegroundColor Cyan
if ($script:fallos.Count -eq 0) {
    Write-Host " MATRIZ COMPLETA - $($script:ok) comprobaciones" -ForegroundColor Green
} else {
    Write-Host " $($script:ok) correctas, $($script:fallos.Count) fallidas" -ForegroundColor Red
    $script:fallos | ForEach-Object { Write-Host "   - $_" -ForegroundColor Red }
}
Write-Host "===================================" -ForegroundColor Cyan
Write-Host ""
