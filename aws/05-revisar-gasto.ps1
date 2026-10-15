<#
.SINOPSIS
    Inventario de todo lo que puede estar consumiendo credito en la cuenta del
    Learner Lab, aunque este apagado.

.DESCRIPCION
    Es de solo lectura: no crea, no modifica y no elimina nada.

    La trampa del Learner Lab es que "detenido" no significa "gratis". Una
    instancia RDS detenida sigue cobrando su almacenamiento y sus backups, una
    EC2 detenida sigue cobrando su volumen EBS, y una IP elastica cobra por hora
    justamente cuando NO esta asociada a una instancia encendida.

    Cost Explorer suele estar denegado en el Learner Lab, asi que el script no
    pregunta cuanto se gasto: enumera lo que existe y marca lo que cobra.

.EJEMPLO
    .\01-credenciales-learner-lab.ps1 ; .\05-revisar-gasto.ps1
#>
[CmdletBinding()]
param(
    [string]$Region = "us-east-1"
)

# AWS CLI escribe avisos por stderr aunque el comando salga bien. Con
# ErrorActionPreference en Stop, PowerShell 5.1 convierte eso en error fatal.
$ErrorActionPreference = "Continue"

function Invoke-Aws {
    param([string[]]$Argumentos)
    $salida = & aws @Argumentos --region $Region --output json 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    if (-not $salida) { return $null }
    return ($salida | ConvertFrom-Json)
}

Write-Host ""
Write-Host "=== Identidad ===" -ForegroundColor Cyan
$yo = Invoke-Aws @("sts", "get-caller-identity")
if (-not $yo) {
    Write-Host "  Las credenciales no sirven. Inicia el lab y corre .\01-credenciales-learner-lab.ps1" -ForegroundColor Red
    exit 1
}
Write-Host "  cuenta $($yo.Account)" -ForegroundColor DarkGray

# ---------------------------------------------------------------------------
# RDS: el sospechoso principal
# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "=== Bases de datos RDS ===" -ForegroundColor Cyan

$rds = Invoke-Aws @("rds", "describe-db-instances")
if (-not $rds -or $rds.DBInstances.Count -eq 0) {
    Write-Host "  No hay instancias RDS. Nada que cobrar por aqui." -ForegroundColor Green
} else {
    foreach ($db in $rds.DBInstances) {
        $color = if ($db.DBInstanceStatus -eq "available") { "Red" } else { "Yellow" }
        Write-Host ("  {0}  [{1}]  {2}  {3} GB  licencia={4}" -f `
            $db.DBInstanceIdentifier, $db.DBInstanceStatus, $db.DBInstanceClass, `
            $db.AllocatedStorage, $db.LicenseModel) -ForegroundColor $color

        if ($db.DBInstanceStatus -eq "available") {
            Write-Host "    ENCENDIDA: cobra computo + licencia + almacenamiento." -ForegroundColor Red
            Write-Host "    Oracle SE2 license-included es la linea mas cara de todo el montaje." -ForegroundColor Red
        } else {
            Write-Host "    Detenida, pero SIGUE cobrando los $($db.AllocatedStorage) GB de almacenamiento." -ForegroundColor Yellow
            Write-Host "    Y AWS la vuelve a encender sola a los 7 dias de detenida." -ForegroundColor Yellow
        }
        Write-Host "    retencion de backups: $($db.BackupRetentionPeriod) dias" -ForegroundColor DarkGray
    }
}

$snapshots = Invoke-Aws @("rds", "describe-db-snapshots", "--snapshot-type", "manual")
if ($snapshots -and $snapshots.DBSnapshots.Count -gt 0) {
    Write-Host ""
    Write-Host "  Snapshots manuales (cobran almacenamiento y sobreviven al borrado de la instancia):" -ForegroundColor Yellow
    foreach ($s in $snapshots.DBSnapshots) {
        Write-Host ("    {0}  {1} GB  {2}" -f $s.DBSnapshotIdentifier, $s.AllocatedStorage, $s.SnapshotCreateTime) -ForegroundColor DarkGray
    }
}

# ---------------------------------------------------------------------------
# EC2 y sus volumenes
# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "=== Instancias EC2 ===" -ForegroundColor Cyan

$ec2 = Invoke-Aws @("ec2", "describe-instances", "--filters", "Name=instance-state-name,Values=pending,running,stopping,stopped")
$instancias = @()
if ($ec2) { $instancias = $ec2.Reservations | ForEach-Object { $_.Instances } }

if ($instancias.Count -eq 0) {
    Write-Host "  No hay instancias." -ForegroundColor Green
} else {
    foreach ($i in $instancias) {
        $nombre = ($i.Tags | Where-Object { $_.Key -eq "Name" }).Value
        $estado = $i.State.Name
        $color = if ($estado -eq "running") { "Red" } else { "Yellow" }
        Write-Host ("  {0}  [{1}]  {2}  {3}" -f $i.InstanceId, $estado, $i.InstanceType, $nombre) -ForegroundColor $color
        if ($estado -ne "running") {
            Write-Host "    Detenida: no cobra horas, pero su volumen EBS si." -ForegroundColor Yellow
        }
    }
}

$volumenes = Invoke-Aws @("ec2", "describe-volumes")
if ($volumenes -and $volumenes.Volumes.Count -gt 0) {
    Write-Host ""
    Write-Host "  Volumenes EBS (cobran siempre, encendida o no):" -ForegroundColor Yellow
    $totalGb = 0
    foreach ($v in $volumenes.Volumes) {
        $totalGb += $v.Size
        Write-Host ("    {0}  {1} GB  {2}  {3}" -f $v.VolumeId, $v.Size, $v.VolumeType, $v.State) -ForegroundColor DarkGray
    }
    Write-Host "    total: $totalGb GB" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------------------
# IPs elasticas: cobran cuando NO estan en uso
# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "=== IPs elasticas ===" -ForegroundColor Cyan

$ips = Invoke-Aws @("ec2", "describe-addresses")
if (-not $ips -or $ips.Addresses.Count -eq 0) {
    Write-Host "  No hay ninguna reservada." -ForegroundColor Green
} else {
    foreach ($ip in $ips.Addresses) {
        if ($ip.InstanceId) {
            Write-Host ("  {0}  asociada a {1}" -f $ip.PublicIp, $ip.InstanceId) -ForegroundColor Green
            Write-Host "    Gratis mientras la instancia este ENCENDIDA. Si esta detenida, cobra por hora." -ForegroundColor DarkGray
        } else {
            Write-Host ("  {0}  SIN ASOCIAR" -f $ip.PublicIp) -ForegroundColor Red
            Write-Host "    Cobra por hora, las 24 horas, sin dar ningun servicio." -ForegroundColor Red
        }
    }
}

# ---------------------------------------------------------------------------
# Otros
# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "=== Otros recursos ===" -ForegroundColor Cyan

$apis = Invoke-Aws @("apigatewayv2", "get-apis")
if ($apis -and $apis.Items.Count -gt 0) {
    foreach ($a in $apis.Items) {
        Write-Host ("  API Gateway  {0}  {1}" -f $a.ApiId, $a.Name) -ForegroundColor DarkGray
    }
    Write-Host "    Cobra por peticion, no por existir: en este proyecto es calderilla." -ForegroundColor DarkGray
}

$nat = Invoke-Aws @("ec2", "describe-nat-gateways")
if ($nat -and ($nat.NatGateways | Where-Object { $_.State -eq "available" }).Count -gt 0) {
    Write-Host "  HAY NAT GATEWAY ACTIVO: cuesta mas que la EC2 y este proyecto no lo necesita." -ForegroundColor Red
    foreach ($n in $nat.NatGateways) {
        Write-Host ("    {0}  {1}" -f $n.NatGatewayId, $n.State) -ForegroundColor Red
    }
}

$lbs = Invoke-Aws @("elbv2", "describe-load-balancers")
if ($lbs -and $lbs.LoadBalancers.Count -gt 0) {
    Write-Host "  HAY LOAD BALANCER: cobra por hora. Este proyecto no usa ninguno." -ForegroundColor Red
}

Write-Host ""
Write-Host "=== Que hacer con esto ===" -ForegroundColor Cyan
Write-Host "  Todo lo que salga en ROJO esta cobrando ahora mismo." -ForegroundColor DarkGray
Write-Host "  Para EP2 la base de AWS ya no se necesita: va en Oracle Cloud." -ForegroundColor DarkGray
Write-Host "  Eliminarla de verdad, no solo detenerla (IRREVERSIBLE):" -ForegroundColor DarkGray
Write-Host "    aws rds delete-db-instance --db-instance-identifier pedidos360-oracle --skip-final-snapshot --delete-automated-backups --region $Region" -ForegroundColor DarkGray
Write-Host "  Detenerla no alcanza: a los 7 dias AWS la vuelve a encender sola." -ForegroundColor DarkGray
Write-Host "  Para ver cuando paso:" -ForegroundColor DarkGray
Write-Host "    aws rds describe-events --source-type db-instance --source-identifier pedidos360-oracle --duration 20160 --region $Region" -ForegroundColor DarkGray
Write-Host ""
