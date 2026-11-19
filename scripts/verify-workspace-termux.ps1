param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [ValidateSet('qa', 'debug', 'release')][string]$Variant = 'qa'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$workspaceVariant = if ($Variant -eq 'release') { 'release' } else { 'debug' }
$variantTask = $Variant.Substring(0, 1).ToUpperInvariant() + $Variant.Substring(1)
$workspaceTask = $workspaceVariant.Substring(0, 1).ToUpperInvariant() + $workspaceVariant.Substring(1)

function Test-Elf16KiB([byte[]]$Bytes) {
    if ($Bytes.Length -lt 64 -or $Bytes[0] -ne 127 -or $Bytes[1] -ne 69 -or
        $Bytes[2] -ne 76 -or $Bytes[3] -ne 70 -or $Bytes[4] -ne 2 -or $Bytes[5] -ne 1) {
        throw 'Expected a little-endian 64-bit ELF file'
    }
    $start = [BitConverter]::ToUInt64($Bytes, 32)
    $stride = [BitConverter]::ToUInt16($Bytes, 54)
    $count = [BitConverter]::ToUInt16($Bytes, 56)
    if ($stride -lt 56 -or $count -eq 0 -or $start -gt $Bytes.Length -or
        ($stride * $count) -gt ($Bytes.Length - $start)) { throw 'Invalid ELF program headers' }
    $loads = 0
    for ($i = 0; $i -lt $count; $i++) {
        $pos = [int]($start + $stride * $i)
        if ([BitConverter]::ToUInt32($Bytes, $pos) -ne 1) { continue }
        $loads++
        $offset = [BitConverter]::ToUInt64($Bytes, $pos + 8)
        $address = [BitConverter]::ToUInt64($Bytes, $pos + 16)
        $alignment = [BitConverter]::ToUInt64($Bytes, $pos + 48)
        if ($alignment -lt 16384 -or ($offset % 16384) -ne ($address % 16384)) { return $false }
    }
    return $loads -gt 0
}

$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ApkPath).Path)
try {
    $entries = @($zip.Entries | Where-Object { $_.FullName -match '^lib/(arm64-v8a|x86_64)/[^/]+\.so$' })
    if (-not $entries.Count) { throw 'APK has no supported 64-bit native libraries' }
    $abis = @()
    foreach ($entry in $entries) {
        if ($entry.Length -gt 128MB) { throw "Native library exceeds verification bound: $($entry.FullName)" }
        $stream = $entry.Open()
        $memory = [System.IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() }
        finally { $stream.Dispose(); $memory.Dispose() }
        if (-not (Test-Elf16KiB $bytes)) { throw "ELF does not support 16 KiB alignment: $($entry.FullName)" }
        $abis += $entry.FullName.Split('/')[1]
        if ($entry.FullName.EndsWith('/libtermux.so')) {
            $abi = $entry.FullName.Split('/')[1]
            $stripped = Join-Path $repo "app/build/intermediates/stripped_native_libs/$Variant/strip${variantTask}DebugSymbols/out/$($entry.FullName)"
            $hash = [System.Security.Cryptography.SHA256]::Create()
            try { $packagedHash = [BitConverter]::ToString($hash.ComputeHash($bytes)).Replace('-', '') }
            finally { $hash.Dispose() }
            if ($packagedHash -ne (Get-FileHash -LiteralPath $stripped -Algorithm SHA256).Hash) {
                throw "APK is not the current $Variant Termux output: $abi"
            }
        }
    }
    foreach ($abi in ($abis | Sort-Object -Unique)) {
        if (@($entries | Where-Object { $_.FullName -eq "lib/$abi/libtermux.so" }).Count -ne 1) {
            throw "Missing or duplicate Termux library: $abi"
        }
        $merged = Join-Path $repo "app/build/intermediates/merged_native_libs/$Variant/merge${variantTask}NativeLibs/out/lib/$abi/libtermux.so"
        $source = Join-Path $repo "workspace/build/intermediates/merged_native_libs/$workspaceVariant/merge${workspaceTask}NativeLibs/out/lib/$abi/libtermux.so"
        if ((Get-FileHash -LiteralPath $merged -Algorithm SHA256).Hash -ne
            (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash) { throw "Termux origin mismatch: $abi" }
    }
    [pscustomobject]@{ nativeLibraries = $entries.Count; elf16KiB = $true; termuxFromWorkspace = $true }
} finally { $zip.Dispose() }
