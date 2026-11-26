# Renders the Oxigen Player logo into all launcher/channel/banner icon assets.
# Uses System.Drawing (native GDI+ on Windows) for crisp anti-aliased output.
# Run:  powershell -ExecutionPolicy Bypass -File scripts\render-icons.ps1
[CmdletBinding()]
param(
    [string]$ResRoot = ''
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
if ([string]::IsNullOrWhiteSpace($ResRoot)) {
    $scriptDir = $PSScriptRoot
    if ([string]::IsNullOrWhiteSpace($scriptDir)) { $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
    if ([string]::IsNullOrWhiteSpace($scriptDir)) { $scriptDir = (Get-Location).Path }
    $ResRoot = Join-Path $scriptDir '..\app\src\main\res'
}
$ResRoot = (Resolve-Path $ResRoot).Path

# ---- Palette (sampled from the Oxigen Player brand logo) ----
$ringTop   = [System.Drawing.Color]::FromArgb(255,  96, 208, 255)
$ringMid   = [System.Drawing.Color]::FromArgb(255,  30, 120, 220)
$ringBot   = [System.Drawing.Color]::FromArgb(255,   8,  48, 130)
$triTop    = [System.Drawing.Color]::FromArgb(255, 205, 243, 255)
$triBot    = [System.Drawing.Color]::FromArgb(255,  64, 168, 245)
$glow      = [System.Drawing.Color]::FromArgb( 78,  70, 190, 255)
$glowEdge  = [System.Drawing.Color]::FromArgb(  0,  70, 190, 255)
$orbitCol  = [System.Drawing.Color]::FromArgb(232, 150, 225, 255)
$orbitGlow = [System.Drawing.Color]::FromArgb( 70, 120, 210, 255)
$bgCenter  = [System.Drawing.Color]::FromArgb(255,  14,  42,  77)
$bgEdge    = [System.Drawing.Color]::FromArgb(255,   4,   7,  14)
$txtWhite  = [System.Drawing.Color]::FromArgb(255, 235, 245, 255)
$txtBlue   = [System.Drawing.Color]::FromArgb(255,  90, 200, 255)

function Add-RoundedRect($path, [double]$x, [double]$y, [double]$w, [double]$h, [double]$r) {
    $d = 2.0 * $r
    $path.AddArc([float]$x, [float]$y, [float]$d, [float]$d, 180, 90)
    $path.AddArc([float]($x + $w - $d), [float]$y, [float]$d, [float]$d, 270, 90)
    $path.AddArc([float]($x + $w - $d), [float]($y + $h - $d), [float]$d, [float]$d, 0, 90)
    $path.AddArc([float]$x, [float]($y + $h - $d), [float]$d, [float]$d, 90, 90)
    $path.CloseFigure()
}

# Emblem: outer glow, orbit swoosh (behind ring), gradient ring, play triangle, comet head.
function Draw-Emblem($g, [double]$cx, [double]$cy, [double]$R) {
    $gr = $R * 1.32
    $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
    $gp.AddEllipse([float]($cx - $gr), [float]($cy - $gr), [float](2 * $gr), [float](2 * $gr))
    $pgb = New-Object System.Drawing.Drawing2D.PathGradientBrush($gp)
    $pgb.CenterColor = $glow
    $pgb.SurroundColors = @($glowEdge)
    $pgb.CenterPoint = New-Object System.Drawing.PointF([float]$cx, [float]$cy)
    $g.FillPath($pgb, $gp)
    $pgb.Dispose(); $gp.Dispose()

    $g.TranslateTransform([float]$cx, [float]$cy)
    $g.RotateTransform(-22)
    $rx = $R * 1.12; $ry = $R * 0.50
    $og = New-Object System.Drawing.Pen($orbitGlow, [float]($R * 0.17))
    $oc = New-Object System.Drawing.Pen($orbitCol,  [float]($R * 0.058))
    foreach ($p in @($og, $oc)) { $p.StartCap = 'Round'; $p.EndCap = 'Round' }
    $g.DrawArc($og, [float](-$rx), [float](-$ry), [float](2 * $rx), [float](2 * $ry), 198, 144)
    $g.DrawArc($oc, [float](-$rx), [float](-$ry), [float](2 * $rx), [float](2 * $ry), 198, 144)
    $a = 342.0 * [Math]::PI / 180.0
    $hx = $rx * [Math]::Cos($a); $hy = $ry * [Math]::Sin($a)
    $hr = $R * 0.10
    $headPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $headPath.AddEllipse([float]($hx - $hr), [float]($hy - $hr), [float](2 * $hr), [float](2 * $hr))
    $hpb = New-Object System.Drawing.Drawing2D.PathGradientBrush($headPath)
    $hpb.CenterColor = [System.Drawing.Color]::FromArgb(255, 235, 250, 255)
    $hpb.SurroundColors = @([System.Drawing.Color]::FromArgb(120, 120, 210, 255))
    $hpb.CenterPoint = New-Object System.Drawing.PointF([float]$hx, [float]$hy)
    $g.FillPath($hpb, $headPath)
    $hpb.Dispose(); $headPath.Dispose()
    $og.Dispose(); $oc.Dispose()
    $g.ResetTransform()

    $outer = New-Object System.Drawing.Drawing2D.GraphicsPath
    $outer.AddEllipse([float]($cx - $R), [float]($cy - $R), [float](2 * $R), [float](2 * $R))
    $ri = $R * 0.70
    $innerP = New-Object System.Drawing.Drawing2D.GraphicsPath
    $innerP.AddEllipse([float]($cx - $ri), [float]($cy - $ri), [float](2 * $ri), [float](2 * $ri))
    $outer.AddPath($innerP, $false)
    $lg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.PointF([float]$cx, [float]($cy - $R))),
        (New-Object System.Drawing.PointF([float]$cx, [float]($cy + $R))),
        $ringTop, $ringBot)
    $g.FillPath($lg, $outer)
    $lg.Dispose(); $outer.Dispose(); $innerP.Dispose()

    $w = $R * 0.44; $h = $R * 0.60
    $tri = New-Object System.Drawing.Drawing2D.GraphicsPath
    $tri.AddPolygon(@(
        (New-Object System.Drawing.PointF([float]($cx - $w * 0.45), [float]($cy - $h))),
        (New-Object System.Drawing.PointF([float]($cx - $w * 0.45), [float]($cy + $h))),
        (New-Object System.Drawing.PointF([float]($cx + $w),       [float]$cy))
    ))
    $tlg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.PointF([float]$cx, [float]($cy - $h))),
        (New-Object System.Drawing.PointF([float]$cx, [float]($cy + $h))),
        $triTop, $triBot)
    $g.FillPath($tlg, $tri)
    $tp = New-Object System.Drawing.Pen($triBot, [float]($R * 0.05))
    $tp.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $g.DrawPath($tp, $tri)
    $tlg.Dispose(); $tri.Dispose(); $tp.Dispose()
}

function Fill-RadialBg($g, $path) {
    $pgb = New-Object System.Drawing.Drawing2D.PathGradientBrush($path)
    $pgb.CenterColor = $bgCenter
    $pgb.SurroundColors = @($bgEdge)
    $g.FillPath($pgb, $path)
    $pgb.Dispose()
}

function Resize-Img($src, [int]$w, [int]$h) {
    $dst = New-Object System.Drawing.Bitmap([int]$w, [int]$h)
    $g = [System.Drawing.Graphics]::FromImage($dst)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.DrawImage($src, 0, 0, $w, $h)
    $g.Dispose()
    return $dst
}

function Save-Png($bmp, [string]$path) {
    $dir = Split-Path $path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
}

# ---- Master renders (high resolution) ----
$M = 1024

# Transparent emblem (adaptive-icon foreground + banner emblem source)
$emblemMaster = New-Object System.Drawing.Bitmap($M, $M)
$emg = [System.Drawing.Graphics]::FromImage($emblemMaster)
$emg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$emg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
Draw-Emblem $emg ($M / 2.0) ($M / 2.0) ($M * 0.30)
$emg.Dispose()

# Full-bleed legacy icon master (rounded dark square + emblem)
$fullMaster = New-Object System.Drawing.Bitmap($M, $M)
$fmg = [System.Drawing.Graphics]::FromImage($fullMaster)
$fmg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$fmg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$rr = New-Object System.Drawing.Drawing2D.GraphicsPath
Add-RoundedRect $rr 0 0 $M $M ($M * 0.235)
Fill-RadialBg $fmg $rr
Draw-Emblem $fmg ($M / 2.0) ($M / 2.0) ($M * 0.335)
$rr.Dispose()
$fmg.Dispose()

# Round legacy icon master (dark circle + emblem)
$roundMaster = New-Object System.Drawing.Bitmap($M, $M)
$rmg = [System.Drawing.Graphics]::FromImage($roundMaster)
$rmg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$rmg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$circ = New-Object System.Drawing.Drawing2D.GraphicsPath
$circ.AddEllipse(0, 0, $M, $M)
Fill-RadialBg $rmg $circ
Draw-Emblem $rmg ($M / 2.0) ($M / 2.0) ($M * 0.335)
$circ.Dispose()
$rmg.Dispose()

# Monochrome silhouette master (themed icons): ring + play triangle, single white shape
$monoMaster = New-Object System.Drawing.Bitmap($M, $M)
$mmg = [System.Drawing.Graphics]::FromImage($monoMaster)
$mmg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$mmg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$mw = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
$R = $M * 0.30
$ringPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$ringPath.AddEllipse([float]($M/2 - $R), [float]($M/2 - $R), [float](2*$R), [float](2*$R))
$ri = $R * 0.70
$ringHole = New-Object System.Drawing.Drawing2D.GraphicsPath
$ringHole.AddEllipse([float]($M/2 - $ri), [float]($M/2 - $ri), [float](2*$ri), [float](2*$ri))
$ringPath.AddPath($ringHole, $false)
$mmg.FillPath($mw, $ringPath)
$tw = $R * 0.40; $th = $R * 0.58
$triP = New-Object System.Drawing.Drawing2D.GraphicsPath
$triP.AddPolygon(@(
    (New-Object System.Drawing.PointF([float]($M/2 - $tw*0.45), [float]($M/2 - $th))),
    (New-Object System.Drawing.PointF([float]($M/2 - $tw*0.45), [float]($M/2 + $th))),
    (New-Object System.Drawing.PointF([float]($M/2 + $tw),       [float]($M/2))
)))
$mmg.FillPath($mw, $triP)
$mw.Dispose(); $ringPath.Dispose(); $ringHole.Dispose(); $triP.Dispose()
$mmg.Dispose()

# ---- Density tables (standard launcher icon px sizes) ----
$iconDensities = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
$fgDensities   = @{ 'mdpi' = 108; 'hdpi' = 162; 'xhdpi' = 216; 'xxhdpi' = 324; 'xxxhdpi' = 432 }

# ---- Remove old Android template assets so only the Oxigen logo remains ----
Get-ChildItem -Path $ResRoot -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^ic_launcher.*\.(webp|png)$' -or $_.Name -match '^ic_channel.*\.(webp|png)$' } |
    Remove-Item -Force -ErrorAction SilentlyContinue
$oldBanner = Join-Path $ResRoot 'drawable\tv_banner.xml'
if (Test-Path $oldBanner) { Remove-Item $oldBanner -Force }

# ---- Emit launcher + channel bitmaps per density ----
foreach ($kv in $iconDensities.GetEnumerator()) {
    $d = $kv.Key; $s = $kv.Value
    $full  = Resize-Img $fullMaster  $s $s
    $round = Resize-Img $roundMaster $s $s
    Save-Png $full  (Join-Path $ResRoot "mipmap-$d\ic_launcher.png")
    Save-Png $round (Join-Path $ResRoot "mipmap-$d\ic_launcher_round.png")
    Save-Png $full  (Join-Path $ResRoot "mipmap-$d\ic_channel.png")
    $full.Dispose(); $round.Dispose()
}
foreach ($kv in $fgDensities.GetEnumerator()) {
    $d = $kv.Key; $s = $kv.Value
    $fg  = Resize-Img $emblemMaster $s $s
    $mono = Resize-Img $monoMaster  $s $s
    $chf = Resize-Img $monoMaster   $s $s
    Save-Png $fg   (Join-Path $ResRoot "mipmap-$d\ic_launcher_foreground.png")
    Save-Png $mono (Join-Path $ResRoot "mipmap-$d\ic_launcher_monochrome.png")
    Save-Png $chf  (Join-Path $ResRoot "mipmap-$d\ic_channel_foreground.png")
    $fg.Dispose(); $mono.Dispose(); $chf.Dispose()
}

# ---- TV banner (640x360): dark gradient + emblem + wordmark ----
$bw = 640; $bh = 360
$banner = New-Object System.Drawing.Bitmap($bw, $bh)
$bg = [System.Drawing.Graphics]::FromImage($banner)
$bg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$bg.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$bg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$lg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.PointF(0, 0)), (New-Object System.Drawing.PointF($bw, $bh)),
    $bgCenter, $bgEdge)
$bg.FillRectangle($lg, 0, 0, $bw, $bh); $lg.Dispose()
$sheen = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.PointF(0, 0)), (New-Object System.Drawing.PointF($bw, 0)),
    [System.Drawing.Color]::FromArgb(0, 255, 255, 255),
    [System.Drawing.Color]::FromArgb(26, 150, 210, 255))
$bg.FillRectangle($sheen, 0, 0, $bw, $bh); $sheen.Dispose()

$embSize = 250
$emb = Resize-Img $emblemMaster $embSize $embSize
$bg.DrawImage($emb, 40, [int](($bh - $embSize) / 2), $embSize, $embSize)
$emb.Dispose()

$fam = 'Segoe UI'
$fOxigen = New-Object System.Drawing.Font($fam, 96, [System.Drawing.FontStyle]::Bold,       [System.Drawing.GraphicsUnit]::Pixel)
$fPlayer = New-Object System.Drawing.Font($fam, 50, [System.Drawing.FontStyle]::Bold,       [System.Drawing.GraphicsUnit]::Pixel)
$fBy     = New-Object System.Drawing.Font($fam, 28, [System.Drawing.FontStyle]::Italic,     [System.Drawing.GraphicsUnit]::Pixel)
$tx = 322
$sb1 = New-Object System.Drawing.SolidBrush($txtWhite)
$sb2 = New-Object System.Drawing.SolidBrush($txtBlue)
$sf = New-Object System.Drawing.StringFormat
$bg.DrawString('OXIGEN',   $fOxigen, $sb1, $tx,     92, $sf)
$bg.DrawString('PLAYER',   $fPlayer, $sb2, $tx + 4, 202, $sf)
$bg.DrawString('by B.P.',  $fBy,     $sb1, $tx + 6, 266, $sf)
$sb1.Dispose(); $sb2.Dispose(); $sf.Dispose()
$fOxigen.Dispose(); $fPlayer.Dispose(); $fBy.Dispose()
Save-Png $banner (Join-Path $ResRoot 'drawable\tv_banner.png')

# ---- In-app logo (drawable/oxigen_logo.png): emblem + wordmark, transparent bg ----
$fLgOxigen = New-Object System.Drawing.Font($fam, 96, [System.Drawing.FontStyle]::Bold,   [System.Drawing.GraphicsUnit]::Pixel)
$fLgPlayer = New-Object System.Drawing.Font($fam, 50, [System.Drawing.FontStyle]::Bold,   [System.Drawing.GraphicsUnit]::Pixel)
$fLgBy     = New-Object System.Drawing.Font($fam, 28, [System.Drawing.FontStyle]::Italic, [System.Drawing.GraphicsUnit]::Pixel)
$lw = 640; $lh = 300
$logo = New-Object System.Drawing.Bitmap($lw, $lh)
$lg2 = [System.Drawing.Graphics]::FromImage($logo)
$lg2.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$lg2.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$lg2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$leSize = 220
$le = Resize-Img $emblemMaster $leSize $leSize
$lg2.DrawImage($le, 6, [int](($lh - $leSize) / 2), $leSize, $leSize)
$le.Dispose()
$ltx = 244
$lsb1 = New-Object System.Drawing.SolidBrush($txtWhite)
$lsb2 = New-Object System.Drawing.SolidBrush($txtBlue)
$lsf = New-Object System.Drawing.StringFormat
$lg2.DrawString('OXIGEN',  $fLgOxigen, $lsb1, $ltx,     34, $lsf)
$lg2.DrawString('PLAYER',  $fLgPlayer, $lsb2, $ltx + 4, 140, $lsf)
$lg2.DrawString('by B.P.', $fLgBy,     $lsb1, $ltx + 6, 202, $lsf)
$lsb1.Dispose(); $lsb2.Dispose(); $lsf.Dispose()
$fLgOxigen.Dispose(); $fLgPlayer.Dispose(); $fLgBy.Dispose()
$lg2.Dispose()
Save-Png $logo (Join-Path $ResRoot 'drawable\oxigen_logo.png')

# ---- Notification small icon (ic_stat_oxigen): white silhouette, standard notif densities ----
# Notification small icons are tinted by the system, so the source is a solid white
# silhouette (ring + play triangle) on a transparent background.
$notifDensities = @{ 'mdpi' = 24; 'hdpi' = 36; 'xhdpi' = 48; 'xxhdpi' = 72; 'xxxhdpi' = 96 }
foreach ($kv in $notifDensities.GetEnumerator()) {
    $d = $kv.Key; $s = $kv.Value
    $ni = Resize-Img $monoMaster $s $s
    Save-Png $ni (Join-Path $ResRoot "drawable-$d\ic_stat_oxigen.png")
    $ni.Dispose()
}

$emblemMaster.Dispose(); $fullMaster.Dispose(); $roundMaster.Dispose(); $monoMaster.Dispose()
Write-Host "render-icons.ps1: Oxigen logo assets written under $ResRoot"

