param(
    [string]$DefaultFile = "app/src/main/res/values/strings.xml",
    [string[]]$Locales = @("values-ja", "values-ko-rKR", "values-ru", "values-zh-rTW"),
    [string]$Output = "app/build/reports/locale-resource-audit.tsv"
)

$ErrorActionPreference = "Stop"
$default = [xml](Get-Content -LiteralPath $DefaultFile -Raw)
$defaultItems = @($default.resources.ChildNodes | Where-Object { $_.LocalName -in @("string", "plurals") })
$defaultNames = @($defaultItems | ForEach-Object name)
$rows = [System.Collections.Generic.List[string]]::new()
$rows.Add("locale`tmissing_count`tmissing_names")

foreach ($locale in $Locales) {
    $dir = Join-Path (Split-Path $DefaultFile -Parent) "../$locale"
    $names = @(
        Get-ChildItem -LiteralPath $dir -Filter '*.xml' -File | ForEach-Object {
            $doc = [xml](Get-Content -LiteralPath $_.FullName -Raw)
            $doc.resources.ChildNodes | Where-Object { $_.LocalName -in @("string", "plurals") } | ForEach-Object name
        }
    )
    $missing = @($defaultNames | Where-Object { $_ -notin $names })
    $rows.Add("$locale`t$($missing.Count)`t$($missing -join ',')")
}

$parent = Split-Path -Parent $Output
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$outputPath = [IO.Path]::GetFullPath($Output)
[IO.File]::WriteAllLines($outputPath, $rows, [Text.UTF8Encoding]::new($false))
Write-Output "Wrote locale audit to $Output"
