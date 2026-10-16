<#
.SINOPSIS
    Obtiene un access token de Microsoft Entra ID y verifica que sirva para el
    JWT Authorizer de AWS API Gateway.

.DESCRIPCION
    Ademas de pedir el token, lo decodifica e inspecciona los claims que deciden
    si API Gateway lo va a aceptar o rechazar. Los tres motivos habituales de 401:

      1. 'iss' es https://sts.windows.net/<tenant>/ en vez de .../v2.0
         -> el registro del backend tiene accessTokenAcceptedVersion en null.
            Debe ser 2. Lo corrige tu companero en el manifiesto de Entra ID.

      2. 'aud' no es el Client ID del backend
         -> se pidio un scope equivocado. Debe ser
            api://<CLIENT_ID_BACKEND>/access_as_user

      3. El scope viaja en 'scp', no en 'scope'
         -> por eso NO hay que configurar Authorization Scopes en API Gateway.

.NOTAS
    Requiere que el registro de aplicacion tenga habilitado "Allow public client
    flows" (Authentication -> Advanced settings). Si tu companero no lo activo,
    ninguno de los dos flujos va a funcionar. Pideselo, o que cree un registro
    aparte tipo public client para pruebas.

.EJEMPLO
    # Modo recomendado: login en el navegador (Authorization Code + PKCE).
    # No requiere "Allow public client flows".
    .\obtener-token.ps1 -Cuenta "Cliente@luissantacruz2026.onmicrosoft.com"

.EJEMPLO
    .\obtener-token.ps1 -CodigoDispositivo
#>
[CmdletBinding(DefaultParameterSetName = "Pkce")]
param(
    <#
        Authorization Code + PKCE. Es el modo recomendado y el que usa por
        defecto, porque funciona con el registro tal como esta: al ser de tipo
        SPA, Entra ID admite este flujo sin secreto de cliente y sin necesidad
        de habilitar "Allow public client flows".

        Ademas es exactamente el flujo que ejecuta el Angular con MSAL, asi que
        el token que obtiene aqui es identico al que usara el navegador.
    #>
    [Parameter(ParameterSetName = "Pkce")]
    [switch]$Pkce,

    [Parameter(ParameterSetName = "Pkce")]
    [string]$RedirectUri = "http://localhost:4200/",

    <#
        Correo de la cuenta con la que se quiere entrar. Al indicarlo se usa
        prompt=login en vez de select_account, que obliga a escribir la
        contrasena: sin eso Entra ID reutiliza la sesion abierta del navegador
        y devuelve un token de la cuenta anterior, aunque se pidiera otra.
    #>
    [Parameter(ParameterSetName = "Pkce")]
    [string]$Cuenta,

    # Resource Owner Password Credentials. Requiere "Allow public client flows".
    [Parameter(Mandatory = $true, ParameterSetName = "Password")]
    [string]$Usuario,

    [Parameter(ParameterSetName = "Password")]
    [SecureString]$Password,

    # Device code. Tambien requiere "Allow public client flows".
    [Parameter(Mandatory = $true, ParameterSetName = "DeviceCode")]
    [switch]$CodigoDispositivo,

    [string]$TenantId = "17dd3345-54db-49ce-8172-092f2ccd50fd",
    [string]$ClientIdFrontend = "6731c641-8037-476d-9d2b-76765ca2ecc3",
    [string]$ClientIdBackend = "fc8de29a-f24d-4173-bfc1-12580819e4c7",

    # Guarda el token en una variable de entorno de la sesion actual.
    [string]$GuardarEnVariable,

    # Emite unicamente el token, sin diagnostico ni mensajes, para que otro
    # script pueda capturarlo con $t = .\obtener-token.ps1 -SoloToken
    [switch]$SoloToken
)

$ErrorActionPreference = "Stop"

$scope = "api://$ClientIdBackend/access_as_user"
$urlToken = "https://login.microsoftonline.com/$TenantId/oauth2/v2.0/token"

function ConvertFrom-JwtPayload {
    param([string]$Jwt)
    $partes = $Jwt.Split('.')
    if ($partes.Count -lt 2) { throw "El token no tiene formato JWT." }

    $payload = $partes[1].Replace('-', '+').Replace('_', '/')
    switch ($payload.Length % 4) {
        2 { $payload += '==' }
        3 { $payload += '=' }
    }
    $bytes = [Convert]::FromBase64String($payload)
    return [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
}

function Show-Diagnostico {
    param([string]$Jwt)

    $claims = ConvertFrom-JwtPayload -Jwt $Jwt
    $issEsperado = "https://login.microsoftonline.com/$TenantId/v2.0"

    Write-Host ""
    Write-Host "=== Claims del token ===" -ForegroundColor Cyan
    Write-Host ("  iss  : {0}" -f $claims.iss)
    Write-Host ("  aud  : {0}" -f $claims.aud)
    Write-Host ("  scp  : {0}" -f $claims.scp)
    Write-Host ("  roles: {0}" -f ($(if ($claims.roles) { $claims.roles -join ", " } else { "(ninguno)" })))
    Write-Host ("  upn  : {0}" -f $claims.preferred_username)

    $expira = [DateTimeOffset]::FromUnixTimeSeconds([int64]$claims.exp).LocalDateTime
    $minutos = [math]::Round(($expira - (Get-Date)).TotalMinutes)
    Write-Host ("  exp  : {0} (en {1} min)" -f $expira, $minutos)

    Write-Host ""
    Write-Host "=== Compatibilidad con el JWT Authorizer de AWS ===" -ForegroundColor Cyan

    $todoOk = $true

    if ($claims.iss -eq $issEsperado) {
        Write-Host "  [OK]    Issuer coincide con el configurado en API Gateway." -ForegroundColor Green
    } else {
        $todoOk = $false
        Write-Host "  [FALLA] Issuer es '$($claims.iss)'" -ForegroundColor Red
        Write-Host "          API Gateway espera '$issEsperado'" -ForegroundColor Red
        if ($claims.iss -like "https://sts.windows.net/*") {
            Write-Host "          Causa: el token es v1. En el manifiesto del registro" -ForegroundColor Yellow
            Write-Host "          Pedidos360-BACKEND-API hay que poner:" -ForegroundColor Yellow
            Write-Host '            "accessTokenAcceptedVersion": 2' -ForegroundColor Yellow
        }
    }

    if ($claims.aud -eq $ClientIdBackend) {
        Write-Host "  [OK]    Audience coincide con el Client ID del backend." -ForegroundColor Green
    } else {
        $todoOk = $false
        Write-Host "  [FALLA] Audience es '$($claims.aud)', se esperaba '$ClientIdBackend'." -ForegroundColor Red
        Write-Host "          Causa: se pidio un scope distinto de $scope" -ForegroundColor Yellow
    }

    if ($claims.PSObject.Properties.Name -contains "scope") {
        Write-Host "  [OK]    El token trae claim 'scope'." -ForegroundColor Green
    } else {
        Write-Host "  [AVISO] El token trae 'scp', no 'scope'." -ForegroundColor Yellow
        Write-Host "          Es lo normal en Entra ID. Por eso las rutas de API Gateway" -ForegroundColor Yellow
        Write-Host "          NO deben llevar Authorization Scopes: darian 403 siempre." -ForegroundColor Yellow
    }

    Write-Host ""
    if ($todoOk) {
        Write-Host "  Resultado: este token deberia pasar el authorizer." -ForegroundColor Green
    } else {
        Write-Host "  Resultado: este token va a ser rechazado con 401." -ForegroundColor Red
    }
    Write-Host ""
}

# ---------------------------------------------------------------------------

if ($PSCmdlet.ParameterSetName -eq "Pkce") {

    # ---- PKCE: verifier aleatorio y su desafio SHA-256 en base64url ----
    $bytes = New-Object byte[] 64
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $verifier = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')

    $sha = [Security.Cryptography.SHA256]::Create()
    $hash = $sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier))
    $challenge = [Convert]::ToBase64String($hash).TrimEnd('=').Replace('+', '-').Replace('/', '_')

    $estado = [guid]::NewGuid().ToString("N")

    $urlAutorizar = "https://login.microsoftonline.com/$TenantId/oauth2/v2.0/authorize" +
        "?client_id=$ClientIdFrontend" +
        "&response_type=code" +
        "&redirect_uri=$([uri]::EscapeDataString($RedirectUri))" +
        "&response_mode=query" +
        "&scope=$([uri]::EscapeDataString("$scope openid profile offline_access"))" +
        "&state=$estado" +
        "&code_challenge=$challenge" +
        "&code_challenge_method=S256" +
        $(if ($Cuenta) {
            "&prompt=login&login_hint=$([uri]::EscapeDataString($Cuenta))"
        } else {
            "&prompt=select_account"
        })

    # Se levanta un receptor en el redirect URI para capturar el 'code' sin que
    # tengas que copiarlo de la barra de direcciones. Si el puerto esta ocupado
    # (por ejemplo, con 'ng serve' corriendo), se pide pegar la URL a mano.
    $listener = $null
    try {
        $listener = New-Object System.Net.HttpListener
        $listener.Prefixes.Add($RedirectUri)
        $listener.Start()
    } catch {
        $listener = $null
    }

    Write-Host ""
    Write-Host "Abriendo el navegador para iniciar sesion..." -ForegroundColor Cyan
    Write-Host "Usa una de las cuentas de prueba (Cliente@... o Admin@...)." -ForegroundColor DarkGray
    Write-Host ""
    Start-Process $urlAutorizar

    $codigo = $null

    if ($listener) {
        Write-Host "Esperando la redireccion en $RedirectUri ..." -ForegroundColor Cyan
        $contexto = $listener.GetContext()
        $codigo = $contexto.Request.QueryString["code"]
        $errorOauth = $contexto.Request.QueryString["error"]

        $html = if ($codigo) {
            "<html><body style='font-family:sans-serif;padding:3rem'><h2>Listo</h2><p>Ya puedes volver a la terminal.</p></body></html>"
        } else {
            "<html><body style='font-family:sans-serif;padding:3rem'><h2>Error</h2><p>$errorOauth</p></body></html>"
        }
        $buffer = [Text.Encoding]::UTF8.GetBytes($html)
        $contexto.Response.ContentType = "text/html; charset=utf-8"
        $contexto.Response.ContentLength64 = $buffer.Length
        $contexto.Response.OutputStream.Write($buffer, 0, $buffer.Length)
        $contexto.Response.Close()
        $listener.Stop()

        if (-not $codigo) { throw "Entra ID devolvio un error en la autorizacion: $errorOauth" }
    } else {
        Write-Host "No se pudo escuchar en $RedirectUri (puerto ocupado)." -ForegroundColor Yellow
        Write-Host "Tras iniciar sesion el navegador quedara en una pagina de error: eso es normal." -ForegroundColor Yellow
        Write-Host "Copia la URL COMPLETA de la barra de direcciones y pegala aqui." -ForegroundColor Yellow
        Write-Host ""
        $urlPegada = Read-Host "URL"
        $m = [regex]::Match($urlPegada, 'code=([^&]+)')
        if (-not $m.Success) { throw "No se encontro el parametro 'code' en la URL." }
        $codigo = [uri]::UnescapeDataString($m.Groups[1].Value)
    }

    # ---- Canje del codigo por el token ----
    # El encabezado Origin es obligatorio: para un registro de tipo SPA, Entra ID
    # solo acepta el canje si viene como peticion de origen cruzado.
    $origen = $RedirectUri.TrimEnd('/')
    try {
        $respuesta = Invoke-RestMethod -Method Post -Uri $urlToken `
            -Headers @{ Origin = $origen } `
            -Body @{
                grant_type    = "authorization_code"
                client_id     = $ClientIdFrontend
                code          = $codigo
                redirect_uri  = $RedirectUri
                code_verifier = $verifier
                scope         = "$scope openid profile offline_access"
            }
    } catch {
        $detalle = $_.ErrorDetails.Message
        Write-Host ""
        Write-Host "Entra ID rechazo el canje del codigo:" -ForegroundColor Red
        Write-Host $detalle -ForegroundColor DarkGray
        Write-Host ""
        if ($detalle -match "AADSTS9002326") {
            Write-Host "AADSTS9002326: falta el encabezado Origin o el registro no es de tipo SPA." -ForegroundColor Yellow
            Write-Host "Verifica que en Authentication exista la plataforma 'Single-page application'" -ForegroundColor Yellow
            Write-Host "con el redirect URI $RedirectUri" -ForegroundColor Yellow
        } elseif ($detalle -match "AADSTS50011") {
            Write-Host "AADSTS50011: el redirect URI no coincide con ninguno registrado." -ForegroundColor Yellow
        } elseif ($detalle -match "AADSTS65001") {
            Write-Host "AADSTS65001: falta conceder el permiso delegado $scope a la SPA." -ForegroundColor Yellow
        }
        throw
    }

} elseif ($PSCmdlet.ParameterSetName -eq "DeviceCode") {

    $urlDevice = "https://login.microsoftonline.com/$TenantId/oauth2/v2.0/devicecode"
    $inicio = Invoke-RestMethod -Method Post -Uri $urlDevice -Body @{
        client_id = $ClientIdFrontend
        scope     = "$scope offline_access openid profile"
    }

    Write-Host ""
    Write-Host $inicio.message -ForegroundColor Cyan
    Write-Host ""
    Start-Process $inicio.verification_uri

    $limite = (Get-Date).AddSeconds([int]$inicio.expires_in)
    $respuesta = $null

    while ((Get-Date) -lt $limite) {
        Start-Sleep -Seconds ([int]$inicio.interval)
        try {
            $respuesta = Invoke-RestMethod -Method Post -Uri $urlToken -Body @{
                grant_type  = "urn:ietf:params:oauth:grant-type:device_code"
                client_id   = $ClientIdFrontend
                device_code = $inicio.device_code
            }
            break
        } catch {
            $detalle = $_.ErrorDetails.Message
            if ($detalle -and $detalle -match "authorization_pending") { continue }
            throw "Fallo el device code flow: $detalle"
        }
    }

    if (-not $respuesta) { throw "Se agoto el tiempo esperando la autenticacion." }

} else {

    if (-not $Usuario) {
        $Usuario = Read-Host "Usuario (ej. Cliente@luissantacruz2026.onmicrosoft.com)"
    }
    if (-not $Password) {
        $Password = Read-Host "Contrasena de $Usuario" -AsSecureString
    }

    $passwordPlano = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password))

    try {
        $respuesta = Invoke-RestMethod -Method Post -Uri $urlToken -Body @{
            grant_type = "password"
            client_id  = $ClientIdFrontend
            scope      = "$scope openid profile"
            username   = $Usuario
            password   = $passwordPlano
        }
    } catch {
        $detalle = $_.ErrorDetails.Message
        Write-Host ""
        Write-Host "Entra ID rechazo la peticion:" -ForegroundColor Red
        Write-Host $detalle -ForegroundColor DarkGray
        Write-Host ""
        if ($detalle -match "AADSTS7000218") {
            Write-Host "AADSTS7000218: el registro no permite flujos de cliente publico." -ForegroundColor Yellow
            Write-Host "Pidele a tu companero: Entra ID -> App registrations -> Pedidos360-FRONTEND" -ForegroundColor Yellow
            Write-Host "  -> Authentication -> Advanced settings -> Allow public client flows = Yes" -ForegroundColor Yellow
        } elseif ($detalle -match "AADSTS50076|AADSTS50079") {
            Write-Host "La cuenta exige MFA. Usa el otro flujo:  .\obtener-token.ps1 -CodigoDispositivo" -ForegroundColor Yellow
        } elseif ($detalle -match "AADSTS50126") {
            Write-Host "Usuario o contrasena incorrectos." -ForegroundColor Yellow
        } elseif ($detalle -match "AADSTS65001") {
            Write-Host "Falta el consentimiento del scope $scope para esta aplicacion." -ForegroundColor Yellow
        }
        throw
    } finally {
        $passwordPlano = $null
        [GC]::Collect()
    }
}

$token = $respuesta.access_token

# Modo silencioso: solo emite el token por la salida estandar, para que otro
# script pueda capturarlo. Lo usa verificar-matriz.ps1.
if ($SoloToken) {
    Write-Output $token
    return
}

Show-Diagnostico -Jwt $token

Write-Host "=== Access token ===" -ForegroundColor Cyan
Write-Host $token
Write-Host ""

if ($GuardarEnVariable) {
    Set-Item -Path "env:$GuardarEnVariable" -Value $token
    Write-Host "Token guardado en `$env:$GuardarEnVariable (solo en esta sesion de PowerShell)." -ForegroundColor Green
    Write-Host ""
}

Set-Clipboard -Value $token
Write-Host "Tambien quedo copiado al portapapeles, listo para pegar en Postman." -ForegroundColor Green
Write-Host ""
