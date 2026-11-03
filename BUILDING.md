# Build 0.1.2

Minecraft 1.20.1, Forge 47.4.28, Java 17.

```powershell
Set-Location -LiteralPath '.\module'
$env:JAVA_HOME = '<your Java 17 installation>'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat build
```

Output is under `module/build/libs/`. A configured dependency cache permits `--offline --no-daemon build`; the first setup may need Gradle/Forge downloads. No Forge/Minecraft runtime library is committed here.

The exact corresponding-source ZIP SHA-256 is `baed109601f7af579d808295c524ae95b7765c8f591df50ba22334bef59744e4`. ZIP timestamps and generated manifest timestamps may differ on rebuild; class/resource comparison is the correspondence check. Retain all license notices.
