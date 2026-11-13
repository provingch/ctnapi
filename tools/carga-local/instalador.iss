; Instalador de Carga CTN (Inno Setup 6). Lo compila .github/workflows/carga-instalador.yml:
;   iscc /DVersion=1.0.0 tools\carga-local\instalador.iss
; Se instala por usuario (sin pedir administrador) en %LOCALAPPDATA%\Programs\CargaCTN.

#ifndef Version
  #define Version "0.0.0"
#endif

[Setup]
AppId={{6C1E3E1B-6F2A-4B8E-9C3D-CA26A0C7E001}
AppName=Carga CTN
AppVersion={#Version}
AppPublisher=Colegio Técnico Nacional - Informática
DefaultDirName={localappdata}\Programs\CargaCTN
DefaultGroupName=Carga CTN
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\dist-instalador
OutputBaseFilename=CargaCTN-Setup-{#Version}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
SetupIconFile=icono.ico
UninstallDisplayIcon={app}\CargaCTN.exe

[Languages]
Name: "es"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "escritorio"; Description: "Crear un acceso directo en el escritorio"; GroupDescription: "Accesos directos:"

[Files]
Source: "..\..\dist\CargaCTN\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\Carga CTN"; Filename: "{app}\CargaCTN.exe"
Name: "{group}\Desinstalar Carga CTN"; Filename: "{uninstallexe}"
Name: "{userdesktop}\Carga CTN"; Filename: "{app}\CargaCTN.exe"; Tasks: escritorio

[Run]
Filename: "{app}\CargaCTN.exe"; Description: "Abrir Carga CTN"; Flags: nowait postinstall skipifsilent
