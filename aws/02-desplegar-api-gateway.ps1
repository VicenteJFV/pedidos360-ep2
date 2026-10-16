<#
.SINOPSIS
    Crea la HTTP API de AWS API Gateway con JWT Authorizer de Microsoft Entra ID,
    todas las rutas funcionales y la configuracion CORS. Idempotente.

.DESCRIPCION
    Reemplaza los ~40 clics de la consola por un comando reproducible. Si lo vuelves
    a ejecutar, reutiliza la API existente y solo crea lo que falte.

    CORS se configura AQUI, en el API Gateway, y no en el codigo del BFF. Asi lo
    pidio el profesor en la evaluacion de EP1. El Gateway responde el preflight
    OPTIONS por si mismo y agrega las cabeceras Access-Control-* a las
    respuestas; por eso ya no se crean rutas OPTIONS hacia el BFF, y las que
    quedaran de EP1 se eliminan.

.EJEMPLO
    .\02-desplegar-api-gateway.ps1 -DestinoBff "http://54.210.11.22:8080"
#>
[CmdletBinding()]
param(
    # URL base publica donde escucha el BFF. SIN barra final.
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^https?://[^/]+$')]
    [string]$DestinoBff,

    [string]$Region = "us-east-1",

    [string]$NombreApi = "API_Gateway_Entrega1",

    # Tenant de Entra ID del equipo.
    [string]$TenantId = "17dd3345-54db-49ce-8172-092f2ccd50fd",

    # Client ID del registro Pedidos360-BACKEND-API. Es el claim 'aud' esperado.
    [string]$Audience = "fc8de29a-f24d-4173-bfc1-12580819e4c7",

    # La pauta de evaluacion exige publicar en la etapa 'Desarrollo' y que el
    # environment.ts del Angular apunte a .../Desarrollo. Importante: NO debe
    # existir tambien un stage '$default'. Con ambos presentes, API Gateway no
    # quita el prefijo de la ruta y busca '/Desarrollo/api/orders' como si fuera
    # un route key, devolviendo 404 en todo.
    [string]$Stage = 'Desarrollo',

    # Origenes que pueden llamar a la API desde un navegador. El del propio
    # Gateway, desde donde se sirve el frontend desplegado (/app/), se agrega
    # solo una vez que se conoce la URL de la API.
    [string[]]$OrigenesPermitidos = @("http://localhost:4200")
)

$ErrorActionPreference = "Stop"

$Issuer = "https://login.microsoftonline.com/$TenantId/v2.0"

function Invoke-Aws {
    param([string[]]$Argumentos, [switch]$PermitirFallo)

    # Ver el comentario equivalente en 00-crear-ec2.ps1: en PowerShell 5.1 el
    # stderr de un ejecutable nativo se convierte en error terminante bajo
    # $ErrorActionPreference = 'Stop', incluso con codigo de salida 0.
    $previo = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $salida = & aws @Argumentos --region $Region --output json 2>&1
        $codigo = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previo
    }

    if ($codigo -ne 0) {
        if ($PermitirFallo) { return $null }
        throw "Fallo el comando: aws $($Argumentos -join ' ')`n$($salida | Out-String)"
    }

    $texto = ($salida | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($texto)) { return $null }
    return ($texto | ConvertFrom-Json)
}

<#
    PowerShell 5.1 elimina las comillas dobles al pasar un argumento a un
    ejecutable nativo, asi que un JSON en linea le llega al AWS CLI como
    {Audience:[...]} y lo rechaza por invalido. Escribirlo a un archivo y pasar
    file://ruta evita el problema por completo: el CLI lee el JSON intacto.
#>
function New-ParametroJson {
    param([string]$Json)
    $ruta = Join-Path $env:TEMP ("apigw-" + [guid]::NewGuid().ToString("N") + ".json")
    Set-Content -LiteralPath $ruta -Value $Json -Encoding ascii
    $script:archivosTemporales += $ruta
    return "file://" + ($ruta -replace '\\', '/')
}

$script:archivosTemporales = @()

Write-Host ""
Write-Host "=== Despliegue de AWS API Gateway (HTTP API) ===" -ForegroundColor Cyan
Write-Host "  Region     : $Region"
Write-Host "  API        : $NombreApi"
Write-Host "  Destino BFF: $DestinoBff"
Write-Host "  Issuer     : $Issuer"
Write-Host "  Audience   : $Audience"
Write-Host ""

# ---------------------------------------------------------------------------
# 1. API HTTP
# ---------------------------------------------------------------------------

$apis = Invoke-Aws @("apigatewayv2", "get-apis")
$api = $apis.Items | Where-Object { $_.Name -eq $NombreApi } | Select-Object -First 1

if ($api) {
    $apiId = $api.ApiId
    Write-Host "[1/5] API existente reutilizada: $apiId" -ForegroundColor Yellow
} else {
    $api = Invoke-Aws @("apigatewayv2", "create-api",
        "--name", $NombreApi,
        "--protocol-type", "HTTP",
        "--description", "EP1 DSY1107 - Punto de entrada unico hacia el BFF Pedidos360")
    $apiId = $api.ApiId
    Write-Host "[1/5] API creada: $apiId" -ForegroundColor Green
}

# CORS en el Gateway. Se aplica siempre, tambien sobre una API existente: asi
# una API creada en EP1 sin CORS queda corregida al volver a correr el script.
# El origen del propio Gateway entra en la lista porque desde ahi se sirve el
# frontend desplegado; el navegador manda Origin en los POST y PUT aunque sean
# del mismo origen.
$origenGateway = ([Uri]$api.ApiEndpoint).GetLeftPart([UriPartial]::Authority)
$todosLosOrigenes = @($OrigenesPermitidos + $origenGateway) | Select-Object -Unique
$origenes = ($todosLosOrigenes | ForEach-Object { '"' + $_ + '"' }) -join ","
$corsJson = '{"AllowOrigins":[' + $origenes + '],' +
            '"AllowMethods":["GET","POST","PUT","DELETE","OPTIONS"],' +
            '"AllowHeaders":["authorization","content-type"],' +
            '"AllowCredentials":false,"MaxAge":3600}'
Invoke-Aws @("apigatewayv2", "update-api", "--api-id", $apiId,
             "--cors-configuration", (New-ParametroJson $corsJson)) | Out-Null
Write-Host "      CORS en el Gateway para: $($todosLosOrigenes -join ', ')" -ForegroundColor Green

# ---------------------------------------------------------------------------
# 2. JWT Authorizer
# ---------------------------------------------------------------------------

$nombreAuth = "JWT-EntraID-Authorizer"
$autorizadores = Invoke-Aws @("apigatewayv2", "get-authorizers", "--api-id", $apiId)
$auth = $autorizadores.Items | Where-Object { $_.Name -eq $nombreAuth } | Select-Object -First 1

if ($auth) {
    $authorizerId = $auth.AuthorizerId
    Write-Host "[2/5] Authorizer existente reutilizado: $authorizerId" -ForegroundColor Yellow
} else {
    # Se arma el JSON a mano: ConvertTo-Json en PowerShell 5.1 colapsa los arreglos
    # de un solo elemento a escalar, y Audience debe ser una lista si o si.
    $jwtConfigJson = '{"Audience":["' + $Audience + '"],"Issuer":"' + $Issuer + '"}'

    # IMPORTANTE: NO se pasa --authorization-scopes en las rutas.
    # El authorizer de AWS valida el claim 'scope'; Entra ID emite 'scp'.
    # Exigir el scope aqui produce 403 en absolutamente todas las peticiones.
    # El scope y los roles se validan en el BFF con Spring Security.
    $auth = Invoke-Aws @("apigatewayv2", "create-authorizer",
        "--api-id", $apiId,
        "--name", $nombreAuth,
        "--authorizer-type", "JWT",
        "--identity-source", '$request.header.Authorization',
        "--jwt-configuration", (New-ParametroJson $jwtConfigJson))

    $authorizerId = $auth.AuthorizerId
    Write-Host "[2/5] Authorizer creado: $authorizerId" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# 3. Rutas e integraciones
# ---------------------------------------------------------------------------

$rutasFuncionales = @(
    @{ Metodo = "GET";    Ruta = "/api/orders" },
    @{ Metodo = "POST";   Ruta = "/api/orders" },
    @{ Metodo = "PUT";    Ruta = "/api/orders/{id}/accept" },
    @{ Metodo = "PUT";    Ruta = "/api/orders/{id}/prepare" },
    @{ Metodo = "PUT";    Ruta = "/api/orders/{id}/ship" },
    @{ Metodo = "PUT";    Ruta = "/api/orders/{id}/deliver" },
    @{ Metodo = "GET";    Ruta = "/api/catalog" },
    @{ Metodo = "POST";   Ruta = "/api/catalog" }
)

$rutasExistentes = (Invoke-Aws @("apigatewayv2", "get-routes", "--api-id", $apiId, "--max-results", "500")).Items
$clavesExistentes = @{}
foreach ($r in $rutasExistentes) { $clavesExistentes[$r.RouteKey] = $r.RouteId }

function Add-Ruta {
    param([string]$Metodo, [string]$Ruta, [bool]$Protegida)

    $claveRuta = "$Metodo $Ruta"
    if ($clavesExistentes.ContainsKey($claveRuta)) {
        Write-Host ("      - {0,-42} (ya existia)" -f $claveRuta) -ForegroundColor DarkGray
        return
    }

    # HTTP APIs sustituyen {id} en la URI de integracion con el valor del path.
    $uriIntegracion = "$script:DestinoBff$Ruta"

    $integracion = Invoke-Aws @("apigatewayv2", "create-integration",
        "--api-id", $script:apiId,
        "--integration-type", "HTTP_PROXY",
        "--integration-method", $Metodo,
        "--integration-uri", $uriIntegracion,
        "--payload-format-version", "1.0")

    $argsRuta = @("apigatewayv2", "create-route",
        "--api-id", $script:apiId,
        "--route-key", $claveRuta,
        "--target", "integrations/$($integracion.IntegrationId)")

    if ($Protegida) {
        $argsRuta += @("--authorization-type", "JWT", "--authorizer-id", $script:authorizerId)
    } else {
        $argsRuta += @("--authorization-type", "NONE")
    }

    Invoke-Aws $argsRuta | Out-Null
    $etiqueta = if ($Protegida) { "JWT" } else { "publica" }
    Write-Host ("      - {0,-42} [{1}]" -f $claveRuta, $etiqueta) -ForegroundColor Green
}

Write-Host "[3/5] Rutas funcionales (protegidas con JWT):"
foreach ($r in $rutasFuncionales) { Add-Ruta -Metodo $r.Metodo -Ruta $r.Ruta -Protegida $true }

# El preflight lo responde el Gateway por su configuracion CORS. Las rutas
# OPTIONS que EP1 reenviaba al BFF ya no se crean, y si quedaron de antes se
# eliminan junto con su integracion: dejarlas seria mantener un segundo lugar
# donde se decide CORS.
$rutasOptions = @($rutasExistentes | Where-Object { $_.RouteKey -like "OPTIONS *" })
if ($rutasOptions.Count -eq 0) {
    Write-Host "[4/5] Preflight resuelto por el Gateway. No hay rutas OPTIONS." -ForegroundColor Green
} else {
    Write-Host "[4/5] Eliminando rutas OPTIONS de EP1 (el preflight ahora es del Gateway):"
    foreach ($r in $rutasOptions) {
        Invoke-Aws @("apigatewayv2", "delete-route", "--api-id", $apiId, "--route-id", $r.RouteId) | Out-Null
        if ($r.Target -match "^integrations/(.+)$") {
            Invoke-Aws @("apigatewayv2", "delete-integration", "--api-id", $apiId,
                         "--integration-id", $Matches[1]) -PermitirFallo | Out-Null
        }
        Write-Host ("      - {0}" -f $r.RouteKey) -ForegroundColor DarkGray
    }
}

# ---------------------------------------------------------------------------
# 4. Stage con auto-deploy
# ---------------------------------------------------------------------------

$stages = Invoke-Aws @("apigatewayv2", "get-stages", "--api-id", $apiId)
$stageExistente = $stages.Items | Where-Object { $_.StageName -eq $Stage } | Select-Object -First 1

if ($stageExistente) {
    Write-Host "[5/5] Stage '$Stage' ya existe." -ForegroundColor Yellow
} else {
    Invoke-Aws @("apigatewayv2", "create-stage",
        "--api-id", $apiId,
        "--stage-name", $Stage,
        "--auto-deploy") | Out-Null
    Write-Host "[5/5] Stage '$Stage' creado con auto-deploy." -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# 5. Resultado
# ---------------------------------------------------------------------------

$urlInvocacion = "https://$apiId.execute-api.$Region.amazonaws.com"
if ($Stage -ne '$default') { $urlInvocacion = "$urlInvocacion/$Stage" }

$resultado = [ordered]@{
    apiId         = $apiId
    region        = $Region
    nombreApi     = $NombreApi
    authorizerId  = $authorizerId
    stage         = $Stage
    destinoBff    = $DestinoBff
    urlInvocacion = $urlInvocacion
    issuer        = $Issuer
    audience      = $Audience
    generado      = (Get-Date).ToString("s")
}

foreach ($tmp in $script:archivosTemporales) {
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
}

$rutaSalida = Join-Path $PSScriptRoot "salida-despliegue.json"
$resultado | ConvertTo-Json | Set-Content -Path $rutaSalida -Encoding utf8

Write-Host ""
Write-Host "=== Listo ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "URL de invocacion publica (esto es lo que le pasas a tu companero" -ForegroundColor Green
Write-Host "para el environment.ts del Angular):" -ForegroundColor Green
Write-Host ""
Write-Host "    $urlInvocacion" -ForegroundColor White
Write-Host ""
Write-Host "Detalle guardado en: $rutaSalida" -ForegroundColor DarkGray
Write-Host ""
Write-Host "Prueba rapida de que el authorizer rechaza sin token (debe dar 401):" -ForegroundColor Cyan
Write-Host "    curl -i $urlInvocacion/api/orders" -ForegroundColor DarkGray
Write-Host ""
