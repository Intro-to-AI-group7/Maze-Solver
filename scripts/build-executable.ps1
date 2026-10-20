Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $root "build\classes"
$requiredJavaMajor = 21
$package = Join-Path $root "build\package"
$dist = Join-Path $root "dist"
$jar = Join-Path $package "MazeSolver.jar"
$staging = Join-Path $root "build\self-extractor"
$payload = Join-Path $staging "MazeSolver-payload.zip"
$wrapperProject = Join-Path $staging "wrapper"
$singleFile = Join-Path $dist "MazeSolver-0.8.1.exe"
$jpackageCommand = Get-Command jpackage -ErrorAction Stop
$javaBin = Split-Path -Parent $jpackageCommand.Source
$javac = Join-Path $javaBin "javac.exe"
$jarTool = Join-Path $javaBin "jar.exe"
$jpackage = $jpackageCommand.Source
if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $jarTool)) {
    throw "Could not find matching javac and jar tools beside $jpackage."
}
$java = Join-Path $javaBin "java.exe"
$versionOutput = (& $env:ComSpec /c "`"$java`" -version 2>&1" | Out-String)
if ($versionOutput -notmatch 'version "(\d+)') {
    throw "Could not determine the Java runtime version for $java."
}
$javaMajor = [int]$Matches[1]
if ($javaMajor -lt $requiredJavaMajor) {
    throw "JDK $requiredJavaMajor or newer is required; found JDK $javaMajor at $javaBin."
}

New-Item -ItemType Directory -Force -Path $classes,$package | Out-Null
$sources = (Get-ChildItem -LiteralPath (Join-Path $root "src") -Filter *.java -Recurse).FullName

Push-Location $root
try {
    & $javac -Xlint:all -d $classes $sources
    if ($LASTEXITCODE -ne 0) {
        throw "Application compilation failed."
    }

    if (Test-Path -LiteralPath $jar) {
        Remove-Item -LiteralPath $jar -Force
    }
    & $jarTool --create --file $jar --main-class app.MS -C $classes .
    if ($LASTEXITCODE -ne 0) {
        throw "JAR creation failed."
    }

    if (Test-Path -LiteralPath $dist) {
        Remove-Item -LiteralPath $dist -Recurse -Force
    }
    & $jpackage `
        --type app-image `
        --name MazeSolver `
        --input $package `
        --main-jar (Split-Path -Leaf $jar) `
        --main-class app.MS `
        --dest $dist `
        --app-version 0.8.1 `
        --vendor "Intro-to-AI-group7"
    if ($LASTEXITCODE -ne 0) {
        throw "Executable packaging failed."
    }

    if (Test-Path -LiteralPath $staging) {
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $staging | Out-Null
    Compress-Archive -Path (Join-Path $dist "MazeSolver") `
        -DestinationPath $payload -CompressionLevel Optimal

    New-Item -ItemType Directory -Force -Path $wrapperProject | Out-Null
    @'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>WinExe</OutputType>
    <TargetFramework>net8.0</TargetFramework>
    <RuntimeIdentifier>win-x64</RuntimeIdentifier>
    <SelfContained>true</SelfContained>
    <PublishSingleFile>true</PublishSingleFile>
    <IncludeNativeLibrariesForSelfExtract>true</IncludeNativeLibrariesForSelfExtract>
    <EnableCompressionInSingleFile>true</EnableCompressionInSingleFile>
    <AssemblyName>MazeSolver</AssemblyName>
    <RootNamespace>MazeSolverLauncher</RootNamespace>
    <Version>0.8.1</Version>
    <AssemblyVersion>0.8.1.0</AssemblyVersion>
    <FileVersion>0.8.1.0</FileVersion>
    <InformationalVersion>0.8.1</InformationalVersion>
    <ImplicitUsings>enable</ImplicitUsings>
  </PropertyGroup>
  <ItemGroup>
    <EmbeddedResource Include="MazeSolver-payload.zip" LogicalName="MazeSolverPayload.zip" />
  </ItemGroup>
</Project>
'@ | Set-Content -LiteralPath (Join-Path $wrapperProject "MazeSolverLauncher.csproj") -Encoding UTF8

    @'
using System.Diagnostics;
using System.IO.Compression;
using System.Reflection;

const string payloadName = "MazeSolverPayload.zip";
bool debug = args.Any(argument =>
    string.Equals(argument, "--debug", StringComparison.OrdinalIgnoreCase));
var extractionDirectory = Path.Combine(Path.GetTempPath(), "MazeSolver-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(extractionDirectory);

try
{
    using Stream payload = Assembly.GetExecutingAssembly()
        .GetManifestResourceStream(payloadName);
    if (payload is null)
    {
        throw new InvalidOperationException("Embedded Maze-Solver payload was not found.");
    }

    ZipFile.ExtractToDirectory(payload, extractionDirectory);
    string applicationDirectory = Path.Combine(extractionDirectory, "MazeSolver");
    string executable = Path.Combine(applicationDirectory, "MazeSolver.exe");
    ProcessStartInfo startInfo;
    if (debug)
    {
        startInfo = new ProcessStartInfo
        {
            FileName = "cmd.exe",
            Arguments = $"/k \"\"{executable}\" --debug\"",
            WorkingDirectory = applicationDirectory,
            UseShellExecute = true
        };
    }
    else
    {
        startInfo = new ProcessStartInfo
        {
            FileName = executable,
            WorkingDirectory = applicationDirectory,
            UseShellExecute = false
        };
    }

    using Process process = Process.Start(startInfo)
        ?? throw new InvalidOperationException("Could not start Maze-Solver.");
    process.WaitForExit();
    return process.ExitCode;
}
finally
{
    try
    {
        Directory.Delete(extractionDirectory, recursive: true);
    }
    catch
    {
        // Temporary cleanup is best effort because the child process may still
        // be releasing a native runtime file when it exits.
    }
}
'@ | Set-Content -LiteralPath (Join-Path $wrapperProject "Program.cs") -Encoding UTF8
    Copy-Item -LiteralPath $payload -Destination (Join-Path $wrapperProject "MazeSolver-payload.zip")

    $dotnet = Get-Command dotnet -ErrorAction Stop
    & $dotnet.Source publish (Join-Path $wrapperProject "MazeSolverLauncher.csproj") `
        --configuration Release --output $staging\publish --nologo
    if ($LASTEXITCODE -ne 0) {
        throw "Single-file executable creation failed."
    }
    Copy-Item -LiteralPath (Join-Path $staging "publish\MazeSolver.exe") `
        -Destination $singleFile -Force
    if (-not (Test-Path -LiteralPath $singleFile)) {
        throw "Single-file executable was not created at $singleFile."
    }
    Remove-Item -LiteralPath (Join-Path $dist "MazeSolver") -Recurse -Force
    Write-Host "Single-file executable created at: $singleFile"
}
finally {
    Pop-Location
}