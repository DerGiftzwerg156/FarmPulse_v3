; FarmPulse Windows setup (Inno Setup 6.3+), built by tools/release/build-installer.ps1.
; Concept and owner decisions: docs/architecture/windows-installer.md
;
;   iscc /DAppVersion=1.7.0 /DSourceImage=<jpackage app image> /DModZip=<FS25_RPSim.zip> /DIconFile=<favicon.ico>
;        /DOutputDir=<dir> /DOutputBaseName=FarmPulse-1.7.0-Setup FarmPulse.iss
;
; The setup writes the player's settings to %USERPROFILE%\.rpsim (where the backend keeps its data):
;   farmpulse-setup.yml                   server.port, rpsim.bridge.path, rpsim.desktop.open-browser
;   local-config\ai-provider.properties   AI provider, model, key, Ollama address (merged; the same file the settings
;                                         page of the tablet writes)
; Both files are written as ASCII with \u escapes, so user names with umlauts survive every code page.

#if Ver < EncodeVer(6, 3, 0)
  #error Inno Setup 6.3 or newer is required
#endif
#ifndef AppVersion
  #error AppVersion missing (/DAppVersion=...)
#endif
#ifndef SourceImage
  #error SourceImage missing (/DSourceImage=<jpackage app image FarmPulse>)
#endif
#ifndef ModZip
  #error ModZip missing (/DModZip=<FS25_RPSim.zip>)
#endif
#ifndef IconFile
  #error IconFile missing (/DIconFile=<favicon.ico>)
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif
#ifndef OutputBaseName
  #define OutputBaseName "FarmPulse-" + AppVersion + "-Setup"
#endif

[Setup]
AppId={{D660A7E2-D6EB-4EAC-949F-7B3627D1B7B3}
AppName=FarmPulse
AppVersion={#AppVersion}
AppVerName=FarmPulse {#AppVersion}
AppPublisher=FarmPulse (Fan-Projekt)
AppPublisherURL=https://github.com/DerGiftzwerg156/FarmPulse_v3
AppSupportURL=https://github.com/DerGiftzwerg156/FarmPulse_v3/issues
AppUpdatesURL=https://github.com/DerGiftzwerg156/FarmPulse_v3/releases
; owner decision: the player chooses "only for me" (default, no admin) or "for all users"
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
DefaultDirName={autopf}\FarmPulse
DisableProgramGroupPage=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir={#OutputDir}
OutputBaseFilename={#OutputBaseName}
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\FarmPulse.exe
UninstallDisplayName=FarmPulse
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
; a running FarmPulse holds files in {app}: close it before installing / uninstalling
CloseApplications=force
RestartApplications=no
; settings are per user on purpose (docs/architecture/windows-installer.md, "Known limit")
UsedUserAreasWarning=no

[Languages]
Name: "german"; MessagesFile: "compiler:Languages\German.isl"

[Tasks]
Name: "copymod"; Description: "Mod FS25_RPSim in den mods-Ordner von Farming Simulator 25 kopieren (ältere Version wird ersetzt)"; GroupDescription: "Mod:"
Name: "autostart"; Description: "FarmPulse beim Anmelden an Windows im Hintergrund starten"; GroupDescription: "Start:"
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; the jpackage image of an older version (Java runtime, jar, web) is replaced as a whole
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#SourceImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; never uninstalled: savegames depend on the mod
Source: "{#ModZip}"; DestDir: "{code:GetModsDir}"; Flags: ignoreversion uninsneveruninstall; Tasks: copymod

[Icons]
Name: "{autoprograms}\FarmPulse"; Filename: "{app}\FarmPulse.exe"; WorkingDir: "{app}"; Comment: "FarmPulse starten (läuft im Hintergrund, Symbol im Infobereich)"
Name: "{autodesktop}\FarmPulse"; Filename: "{app}\FarmPulse.exe"; WorkingDir: "{app}"; Comment: "FarmPulse starten"; Tasks: desktopicon
; the settings belong to the user who runs the setup, so the autostart does too
Name: "{userstartup}\FarmPulse"; Filename: "{app}\FarmPulse.exe"; WorkingDir: "{app}"; Comment: "FarmPulse im Hintergrund starten"; Tasks: autostart

[Run]
Filename: "{app}\FarmPulse.exe"; WorkingDir: "{app}"; Description: "FarmPulse jetzt starten"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
; the backend runs without a window: stop this user's FarmPulse before its files are removed
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM FarmPulse.exe /FI ""USERNAME eq {username}"""; Flags: runhidden; RunOnceId: "StopFarmPulse"

[Code]
const
  ProviderLater = 0;
  ProviderNone = 1;
  ProviderOpenAi = 2;
  ProviderAnthropic = 3;
  ProviderGemini = 4;
  ProviderOllama = 5;
  DefaultOllamaUrl = 'http://localhost:11434';
  DefaultPort = '8080';

var
  PreviousSettings: Boolean;
  UpdatePage: TInputOptionWizardPage;
  AiPage: TWizardPage;
  AiProvider: TNewComboBox;
  AiKeyLabel, AiModelLabel, AiUrlLabel, AiHint: TNewStaticText;
  AiKey: TPasswordEdit;
  AiModel, AiUrl: TNewEdit;
  FolderPage: TInputDirWizardPage;
  StartPage: TWizardPage;
  PortEdit: TNewEdit;
  OpenBrowser: TNewCheckBox;

{ ---------------------------------------------------------------- files }

function DataDir: String;
begin
  Result := ExpandConstant('{%USERPROFILE}') + '\.rpsim';
end;

function SetupYml: String;
begin
  Result := DataDir + '\farmpulse-setup.yml';
end;

function AiFile: String;
begin
  Result := DataDir + '\local-config\ai-provider.properties';
end;

{ settings pages are shown on a new installation, or on an update when the player chose to set them again }
function WriteSettings: Boolean;
begin
  Result := (not PreviousSettings) or UpdatePage.Values[1];
end;

function GetModsDir(Param: String): String;
begin
  Result := FolderPage.Values[1];
end;

{ ---------------------------------------------------------------- escaping (ASCII only) }

{ Every character above ASCII as \uXXXX - understood by YAML double-quoted strings and Java properties alike }
function EscapeNonAscii(const S: String): String;
var
  I: Integer;
begin
  Result := '';
  for I := 1 to Length(S) do
    if (Ord(S[I]) < 32) or (Ord(S[I]) > 126) then
      Result := Result + Format('\u%.4x', [Ord(S[I])])
    else
      Result := Result + S[I];
end;

{ YAML double-quoted string; Windows paths with forward slashes (as the backend's own defaults) }
function YamlPath(const Path: String): String;
var
  S: String;
begin
  S := Trim(Path);
  StringChangeEx(S, '\', '/', True);
  StringChangeEx(S, '"', '\"', True);
  Result := '"' + EscapeNonAscii(S) + '"';
end;

function PropertyValue(const Value: String): String;
var
  S: String;
begin
  S := Trim(Value);
  StringChangeEx(S, '\', '\\', True);
  Result := EscapeNonAscii(S);
end;

{ ---------------------------------------------------------------- AI provider }

function ProviderId(Index: Integer): String;
begin
  case Index of
    ProviderNone: Result := 'NONE';
    ProviderOpenAi: Result := 'OPENAI';
    ProviderAnthropic: Result := 'ANTHROPIC';
    ProviderGemini: Result := 'GEMINI';
    ProviderOllama: Result := 'OLLAMA';
  else
    Result := '';
  end;
end;

function NeedsKey(Index: Integer): Boolean;
begin
  Result := (Index = ProviderOpenAi) or (Index = ProviderAnthropic) or (Index = ProviderGemini);
end;

procedure UpdateAiControls(Sender: TObject);
var
  Index: Integer;
begin
  Index := AiProvider.ItemIndex;
  AiKeyLabel.Visible := NeedsKey(Index);
  AiKey.Visible := NeedsKey(Index);
  AiModelLabel.Visible := Index >= ProviderOpenAi;
  AiModel.Visible := Index >= ProviderOpenAi;
  AiUrlLabel.Visible := Index = ProviderOllama;
  AiUrl.Visible := Index = ProviderOllama;
  case Index of
    ProviderLater:
      AiHint.Caption := 'Den KI-Anbieter kannst du jederzeit im Tablet unter Einstellungen festlegen. '
        + 'Eine bereits gespeicherte KI-Einstellung bleibt unverändert.';
    ProviderNone:
      AiHint.Caption := 'Ohne KI antworten die Charaktere mit passenden, vorformulierten Texten. '
        + 'Alle Zahlen berechnet FarmPulse immer selbst.';
    ProviderOllama:
      AiHint.Caption := 'Ollama läuft auf deinem PC oder im Heimnetz und braucht keinen API-Schlüssel. '
        + 'Modell leer lassen = Standardmodell.';
  else
    AiHint.Caption := 'Der Schlüssel wird nur auf diesem PC gespeichert (' + AiFile + ') und nie wieder angezeigt. '
      + 'Leer lassen = später im Tablet eintragen bzw. bisherigen Schlüssel behalten. Modell leer lassen = Standardmodell.';
  end;
end;

function AddLabel(Page: TWizardPage; const Caption: String; Top: Integer): TNewStaticText;
begin
  Result := TNewStaticText.Create(Page);
  Result.Parent := Page.Surface;
  Result.Caption := Caption;
  Result.Top := Top;
  Result.Width := Page.SurfaceWidth;
end;

function AddEdit(Page: TWizardPage; Top: Integer): TNewEdit;
begin
  Result := TNewEdit.Create(Page);
  Result.Parent := Page.Surface;
  Result.Top := Top;
  Result.Width := Page.SurfaceWidth;
end;

procedure CreateAiPage(AfterId: Integer);
var
  Top: Integer;
begin
  AiPage := CreateCustomPage(AfterId, 'KI-Anbieter',
    'Wer formuliert die Texte der Charaktere? Die KI schreibt nur Texte – Geld, Preise und Entscheidungen berechnet FarmPulse selbst.');
  Top := 0;
  AddLabel(AiPage, 'Anbieter:', Top);
  AiProvider := TNewComboBox.Create(AiPage);
  AiProvider.Parent := AiPage.Surface;
  AiProvider.Style := csDropDownList;
  AiProvider.Top := Top + ScaleY(16);
  AiProvider.Width := AiPage.SurfaceWidth;
  AiProvider.Items.Add('Später im Tablet einrichten (bzw. unverändert lassen)');
  AiProvider.Items.Add('Ohne KI – vorformulierte Texte');
  AiProvider.Items.Add('OpenAI');
  AiProvider.Items.Add('Anthropic (Claude)');
  AiProvider.Items.Add('Google Gemini');
  AiProvider.Items.Add('Ollama (lokal, ohne API-Schlüssel)');
  AiProvider.ItemIndex := StrToIntDef(GetPreviousData('AiProvider', '0'), ProviderLater);
  AiProvider.OnChange := @UpdateAiControls;

  Top := Top + ScaleY(48);
  AiKeyLabel := AddLabel(AiPage, 'API-Schlüssel:', Top);
  AiKey := TPasswordEdit.Create(AiPage);
  AiKey.Parent := AiPage.Surface;
  AiKey.Top := Top + ScaleY(16);
  AiKey.Width := AiPage.SurfaceWidth;

  Top := Top + ScaleY(48);
  AiUrlLabel := AddLabel(AiPage, 'Adresse des Ollama-Servers:', Top);
  AiUrl := AddEdit(AiPage, Top + ScaleY(16));
  AiUrl.Text := GetPreviousData('AiOllamaUrl', DefaultOllamaUrl);
  { Ollama has no key: its address takes the place of the key field }
  AiUrlLabel.Top := AiKeyLabel.Top;
  AiUrl.Top := AiKey.Top;

  AiModelLabel := AddLabel(AiPage, 'Modell (optional):', Top);
  AiModel := AddEdit(AiPage, Top + ScaleY(16));
  AiModel.Text := GetPreviousData('AiModel', '');

  AiHint := AddLabel(AiPage, '', Top + ScaleY(52));
  AiHint.AutoSize := False;
  AiHint.WordWrap := True;
  AiHint.Height := ScaleY(64);
  UpdateAiControls(nil);
end;

{ Sets Key=Value in Lines: replaces the line of the key (as Java's Properties.store writes it) or appends one }
procedure SetProperty(var Lines: TArrayOfString; const Key, Value: String);
var
  I, N: Integer;
  Line: String;
begin
  N := GetArrayLength(Lines);
  for I := 0 to N - 1 do
  begin
    Line := TrimLeft(Lines[I]);
    if (Pos(Key + '=', Line) = 1) or (Pos(Key + ':', Line) = 1) or (Pos(Key + ' ', Line) = 1) then
    begin
      Lines[I] := Key + '=' + Value;
      Exit;
    end;
  end;
  SetArrayLength(Lines, N + 1);
  Lines[N] := Key + '=' + Value;
end;

procedure SaveAiSettings;
var
  Lines: TArrayOfString;
  Index: Integer;
  Prefix: String;
begin
  Index := AiProvider.ItemIndex;
  if Index = ProviderLater then
    Exit;
  if not LoadStringsFromFile(AiFile, Lines) then
  begin
    SetArrayLength(Lines, 1);
    Lines[0] := '#Local AI provider settings - never commit this file';
  end;
  SetProperty(Lines, 'provider', ProviderId(Index));
  Prefix := Lowercase(ProviderId(Index)) + '.';
  if NeedsKey(Index) and (Trim(AiKey.Text) <> '') then
    SetProperty(Lines, Prefix + 'apiKey', PropertyValue(AiKey.Text));
  if (Index >= ProviderOpenAi) and (Trim(AiModel.Text) <> '') then
    SetProperty(Lines, Prefix + 'model', PropertyValue(AiModel.Text));
  if Index = ProviderOllama then
    SetProperty(Lines, Prefix + 'baseUrl', PropertyValue(AiUrl.Text));
  ForceDirectories(ExtractFileDir(AiFile));
  if not SaveStringsToFile(AiFile, Lines, False) then
    MsgBox('Die KI-Einstellungen konnten nicht gespeichert werden (' + AiFile + '). '
      + 'Bitte im Tablet unter Einstellungen eintragen.', mbError, MB_OK);
end;

{ ---------------------------------------------------------------- start page }

procedure CreateStartPage(AfterId: Integer);
begin
  StartPage := CreateCustomPage(AfterId, 'Start',
    'Wie soll FarmPulse starten? FarmPulse läuft im Hintergrund, ein Symbol im Infobereich neben der Uhr öffnet und beendet es.');
  AddLabel(StartPage, 'Port (Adresse http://localhost:<Port>):', 0);
  PortEdit := AddEdit(StartPage, ScaleY(16));
  PortEdit.Width := ScaleX(80);
  PortEdit.Text := GetPreviousData('Port', DefaultPort);
  with AddLabel(StartPage, 'Ist der Port belegt, nimmt FarmPulse automatisch den nächsten freien Port.', ScaleY(44)) do
  begin
    AutoSize := False;
    WordWrap := True;
    Height := ScaleY(32);
  end;
  OpenBrowser := TNewCheckBox.Create(StartPage);
  OpenBrowser.Parent := StartPage.Surface;
  OpenBrowser.Top := ScaleY(84);
  OpenBrowser.Width := StartPage.SurfaceWidth;
  OpenBrowser.Caption := 'Browser beim Start öffnen';
  OpenBrowser.Checked := GetPreviousData('OpenBrowser', '1') = '1';
end;

procedure SaveSetupYml;
var
  Lines: TArrayOfString;
  OpenBrowserValue: String;
begin
  if OpenBrowser.Checked then
    OpenBrowserValue := 'true'
  else
    OpenBrowserValue := 'false';
  SetArrayLength(Lines, 9);
  Lines[0] := '# Geschrieben vom FarmPulse-Setup. Aendern: Setup erneut ausfuehren ("Einstellungen neu festlegen").';
  Lines[1] := '# Eigene Einstellungen gehoeren in application-local.yml in diesem Ordner - die gewinnen.';
  Lines[2] := 'server:';
  Lines[3] := '  port: ' + IntToStr(StrToIntDef(Trim(PortEdit.Text), 8080));
  Lines[4] := 'rpsim:';
  Lines[5] := '  bridge:';
  Lines[6] := '    path: ' + YamlPath(FolderPage.Values[0]);
  Lines[7] := '  desktop:';
  Lines[8] := '    open-browser: ' + OpenBrowserValue;
  ForceDirectories(DataDir);
  if not SaveStringsToFile(SetupYml, Lines, False) then
    MsgBox('Die Einstellungen konnten nicht gespeichert werden (' + SetupYml + ').', mbError, MB_OK);
end;

{ ---------------------------------------------------------------- wizard }

procedure InitializeWizard;
var
  FsDir: String;
begin
  PreviousSettings := FileExists(SetupYml) or FileExists(AiFile);
  UpdatePage := CreateInputOptionPage(wpSelectDir, 'Einstellungen',
    'FarmPulse ist auf diesem PC bereits eingerichtet.',
    'Sollen die bisherigen Einstellungen (KI-Anbieter, Ordner, Port) bleiben?', True, False);
  UpdatePage.Add('Bisherige Einstellungen beibehalten');
  UpdatePage.Add('Einstellungen neu festlegen');
  UpdatePage.Values[0] := True;

  CreateAiPage(UpdatePage.ID);

  FsDir := ExpandConstant('{userdocs}') + '\My Games\FarmingSimulator2025';
  FolderPage := CreateInputDirPage(AiPage.ID, 'Ordner von Farming Simulator 25',
    'Wo tauschen Mod und FarmPulse ihre Dateien aus?',
    'Der Austauschordner liegt im Dokumente-Ordner von Farming Simulator 25 (auch wenn OneDrive ihn verschoben hat). '
    + 'Der Mod legt ihn beim ersten Laden eines Spielstands an – er muss noch nicht existieren.',
    False, '');
  FolderPage.Add('Austauschordner (modSettings\FS25_RPSim):');
  FolderPage.Add('Mod-Ordner von Farming Simulator 25 (mods), für „Mod kopieren“:');
  FolderPage.Values[0] := GetPreviousData('BridgePath', FsDir + '\modSettings\FS25_RPSim');
  FolderPage.Values[1] := GetPreviousData('ModsPath', FsDir + '\mods');

  CreateStartPage(FolderPage.ID);
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if PageID = UpdatePage.ID then
    Result := not PreviousSettings
  else if (PageID = AiPage.ID) or (PageID = FolderPage.ID) or (PageID = StartPage.ID) then
    Result := not WriteSettings;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  Port: Integer;
  Url: String;
begin
  Result := True;
  if CurPageID = AiPage.ID then
  begin
    Url := Lowercase(Trim(AiUrl.Text));
    if (AiProvider.ItemIndex = ProviderOllama) and (Pos('http://', Url) <> 1) and (Pos('https://', Url) <> 1) then
    begin
      MsgBox('Die Ollama-Adresse muss mit http:// oder https:// beginnen, z. B. ' + DefaultOllamaUrl + '.', mbError, MB_OK);
      Result := False;
    end;
  end
  else if CurPageID = FolderPage.ID then
  begin
    if (Trim(FolderPage.Values[0]) = '') or (Trim(FolderPage.Values[1]) = '') then
    begin
      MsgBox('Bitte beide Ordner angeben.', mbError, MB_OK);
      Result := False;
    end;
  end
  else if CurPageID = StartPage.ID then
  begin
    Port := StrToIntDef(Trim(PortEdit.Text), 0);
    if (Port < 1024) or (Port > 65535) then
    begin
      MsgBox('Bitte einen Port zwischen 1024 und 65535 angeben (Standard: ' + DefaultPort + ').', mbError, MB_OK);
      Result := False;
    end;
  end;
end;

procedure RegisterPreviousData(PreviousDataKey: Integer);
begin
  SetPreviousData(PreviousDataKey, 'AiProvider', IntToStr(AiProvider.ItemIndex));
  SetPreviousData(PreviousDataKey, 'AiModel', AiModel.Text);
  SetPreviousData(PreviousDataKey, 'AiOllamaUrl', AiUrl.Text);
  SetPreviousData(PreviousDataKey, 'BridgePath', FolderPage.Values[0]);
  SetPreviousData(PreviousDataKey, 'ModsPath', FolderPage.Values[1]);
  SetPreviousData(PreviousDataKey, 'Port', Trim(PortEdit.Text));
  if OpenBrowser.Checked then
    SetPreviousData(PreviousDataKey, 'OpenBrowser', '1')
  else
    SetPreviousData(PreviousDataKey, 'OpenBrowser', '0');
end;

{ FarmPulse runs without a window, so the Restart Manager may not see it: stop this user's FarmPulse first }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM FarmPulse.exe /FI "USERNAME eq ' + GetUserNameString + '"', '',
    SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := '';
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and WriteSettings then
  begin
    SaveSetupYml;
    SaveAiSettings;
  end;
end;

{ ---------------------------------------------------------------- uninstall }

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if (CurUninstallStep = usPostUninstall) and DirExists(DataDir) and not UninstallSilent then
    if MsgBox('Spielstände und Einstellungen ebenfalls löschen?' + #13#10#13#10
      + 'Ordner: ' + DataDir + #13#10
      + '(Datenbank mit Charakteren, Mails und Krediten, KI-Schlüssel, Protokolle)' + #13#10#13#10
      + 'Bei „Nein“ bleiben sie für eine spätere Installation erhalten.',
      mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
      DelTree(DataDir, True, True, True);
end;
