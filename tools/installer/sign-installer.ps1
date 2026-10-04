<#
    Signs the installer, so Windows shows a publisher instead of "未知发布者".

    Two ways to use it:

      # an existing certificate (a .pfx from a CA, or one already in the store)
      .\sign-installer.ps1 -Setup dist\dstokencheck-1.1.5-setup.exe -Pfx mycert.pfx -Password secret

      # a throwaway self-signed one, to prove the mechanism / for your own machines
      .\sign-installer.ps1 -Setup dist\dstokencheck-1.1.5-setup.exe -SelfSigned -PublisherName "fcymz"

    Important, and the reason this script prints the resulting status: a *self-signed* certificate
    only removes the warning on machines that trust it (yours, or ones your IT manages). For the
    public, only a certificate chaining to a CA in the Microsoft Trusted Root Program helps — see
    tools\installer\README.md.
#>
param(
    [Parameter(Mandatory = $true)][string]$Setup,
    [string]$Pfx = "",
    [string]$Password = "",
    [string]$Thumbprint = "",
    [string]$Subject = "",
    [string]$TimestampUrl = "http://timestamp.digicert.com",
    [switch]$SelfSigned,
    [string]$PublisherName = "",
    [switch]$KeepCertificate
)

$ErrorActionPreference = "Stop"
$Setup = (Resolve-Path $Setup).Path

if (-not (Test-Path $Setup)) { throw "not found: $Setup" }
if ([System.IO.Path]::GetExtension($Setup) -ne ".exe") { throw "only .exe files can be Authenticode-signed" }

$created = $null   # a certificate this run made, removed again unless asked to keep it

# ---------------------------------------------------------------- pick the certificate
if ($SelfSigned) {
    if ([string]::IsNullOrWhiteSpace($PublisherName)) { $PublisherName = "dstokencheck" }
    "==> creating a self-signed code-signing certificate for '$PublisherName'"
    $created = New-SelfSignedCertificate `
        -Type CodeSigningCert `
        -Subject "CN=$PublisherName, O=$PublisherName, C=CN" `
        -KeyUsage DigitalSignature `
        -FriendlyName "dstokencheck test signing ($PublisherName)" `
        -CertStoreLocation "Cert:\CurrentUser\My" `
        -NotAfter (Get-Date).AddYears(3)
    $cert = $created
} elseif (-not [string]::IsNullOrWhiteSpace($Pfx)) {
    "==> importing $Pfx"
    if (-not (Test-Path $Pfx)) { throw "not found: $Pfx" }
    $secure = ConvertTo-SecureString -String $Password -AsPlainText -Force
    # Imported into the store because signing needs a key Windows can actually use; removed again
    # at the end unless -KeepCertificate says otherwise.
    $imported = Import-PfxCertificate -FilePath $Pfx -Password $secure -CertStoreLocation "Cert:\CurrentUser\My"
    $created = $imported
    $cert = Get-Item "Cert:\CurrentUser\My\$($imported.Thumbprint)"
} elseif (-not [string]::IsNullOrWhiteSpace($Thumbprint)) {
    $cert = Get-Item "Cert:\CurrentUser\My\$Thumbprint"
} elseif (-not [string]::IsNullOrWhiteSpace($Subject)) {
    $cert = Get-ChildItem "Cert:\CurrentUser\My" |
        Where-Object { $_.Subject -like "*$Subject*" -and $_.HasPrivateKey } |
        Select-Object -First 1
    if (-not $cert) { throw "no certificate with a private key matching '$Subject' in Cert:\CurrentUser\My" }
} else {
    throw "give one of -Pfx, -Thumbprint, -Subject or -SelfSigned"
}

"    subject    : $($cert.Subject)"
"    thumbprint : $($cert.Thumbprint)"
"    expires    : $($cert.NotAfter)"
"    issuer     : $($cert.Issuer)"
if ($cert.Subject -eq $cert.Issuer) {
    "    NOTE: this is self-signed — it only counts as a publisher on machines that trust it."
}

# ---------------------------------------------------------------- sign
$signArgs = @{
    FilePath        = $Setup
    Certificate     = $cert
    HashAlgorithm   = "SHA256"
}
if (-not [string]::IsNullOrWhiteSpace($TimestampUrl)) { $signArgs.TimestampServer = $TimestampUrl }

"==> signing $(Split-Path -Leaf $Setup)"
try {
    $result = Set-AuthenticodeSignature @signArgs
} catch {
    "    signing failed, retrying without a timestamp server ($($_.Exception.Message))"
    $signArgs.Remove("TimestampServer")
    $result = Set-AuthenticodeSignature @signArgs
}

# ---------------------------------------------------------------- report what Windows thinks
$check = Get-AuthenticodeSignature $Setup
""
"    status      : $($check.Status)"
"    status text : $($check.StatusMessage)"
if ($check.SignerCertificate) {
    "    signer      : $($check.SignerCertificate.Subject)"
    "    timestamp   : $(if ($check.TimeStamperCertificate) { $check.TimeStamperCertificate.Subject } else { '(none)' })"
}
""
switch ($check.Status) {
    "Valid" { "==> Windows trusts this signature: the file will show '$($check.SignerCertificate.Subject.Split(',')[0])' as its publisher." }
    default {
        @(
            "==> The signature is embedded, but this machine does not trust the chain yet, so it still",
            "    reports an unknown publisher. To trust a self-signed certificate on a machine you own:",
            "",
            "        Import-Certificate -FilePath mycert.cer -CertStoreLocation Cert:\CurrentUser\Root",
            "        Import-Certificate -FilePath mycert.cer -CertStoreLocation Cert:\CurrentUser\TrustedPublisher",
            "",
            "    That is a real change to the machine's trust store — do it deliberately, and never ask",
            "    anybody else to. For the public, only a certificate from a CA in the Microsoft Trusted",
            "    Root Program removes the warning."
        ) | ForEach-Object { $_ }
    }
}

if ($created -and -not $KeepCertificate) {
    "==> removing the certificate from the store again (pass -KeepCertificate to keep it)"
    Remove-Item "Cert:\CurrentUser\My\$($created.Thumbprint)" -Force -ErrorAction SilentlyContinue
}

if ($result) { exit 0 }
