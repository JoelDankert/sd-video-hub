# SD Video Hub

Code-only-Snapshot eines FPV-Videoarchivs für Linux/Raspberry Pi: automatischer Kartenimport, MP4-Kompression, Browser-Player und virtuelle Flugabschnitte anhand von OSD-Schriften. Keine Videos, Bilder, Datenbanken oder Zugangsdaten enthalten.

> **ACHTUNG: AUTOMATISCHE LÖSCHUNG ALLER KARTENINHALTE.** Der Server überwacht Wechselmedien-/USB-Partitionen automatisch, nutzt vorhandene Mounts und versucht sonst selbst zu mounten. Nach dem Kopieren ins Staging und dessen Prüfung löscht er **alle Inhalte des Quell-Mounts – noch VOR der Kompression und Fluganalyse**, ohne zusätzliche Benutzerbestätigung. Videos werden mit Dateigröße und SHA256 geprüft, andere Dateien nur mit ihrer Größe. **Nicht-Videodateien werden NICHT dauerhaft archiviert**; sie verschwinden beim späteren Staging-Cleanup. Eine erfolgreiche Kopierprüfung ist keine Garantie für ein lesbares Video oder ein dauerhaftes Backup.
>
> Nur auf einem dedizierten Gerät mit ausdrücklich entbehrlichen oder bereits gesicherten Karten verwenden. **Kein allgemeiner Desktop-Server:** Auch andere angeschlossene USB-Datenträger können erfasst werden. Vor jedem Start fremde Datenträger entfernen. Loopback und ein Passwort verhindern keine lokalen Löschvorgänge.

## Funktionen

- Kopier-, Kompressions- und Analysestatus im Webinterface; SQLite-Zustand für Wiederaufnahme bestimmter Verarbeitungsschritte.
- Archivierung als H.264/AAC-MP4, Vorschaubilder und Wiedergabe mit HTTP-Range-Unterstützung.
- OSD-Erkennung für `DISARMED`, `MENU` und `STATS`; Nicht-Flug wird virtuell übersprungen, das Archivvideo bleibt dabei unverändert.
- Tonsteuerung und 4:3-Crop im Player. Ein experimenteller 480p-Endpunkt ist noch im Backend vorhanden, aber nicht als normale Playeroption freigeschaltet; er benötigt den Pi-Encoder `h264_v4l2m2m` und ist kein zugesicherter Wiedergabepfad.
- Download der vollständigen Archivdatei oder temporärer Nur-Flug-Export, siehe [DOWNLOADS.md](DOWNLOADS.md).

## Installation und Start

Voraussetzungen: Linux, Python 3.10+ mit `venv`/pip, ausreichend Speicher für Staging **plus** Archiv und temporäre Exporte. Benötigte Systempakete unter Debian/Raspberry Pi OS: `python3`, `python3-venv`, `ffmpeg` (einschließlich `ffprobe`, `libx264` und AAC), `util-linux` (`lsblk`, `mount`, `umount`) und `udisks2` (`udisksctl`). Systempakete durch die Administration installieren lassen; Mount-Berechtigungen/Polkit müssen zum Betriebskonto passen.

Das Backend verwendet nur die Python-Standardbibliothek. Der Detektor läuft als separater Prozess in der fest erwarteten Umgebung `.venv-template`; seine einzigen zusätzlichen Python-Abhängigkeiten sind OpenCV und NumPy. Pillow wird im aktuellen Produktionspfad nicht verwendet.

Im Projektverzeichnis vorbereiten:

```sh
cp config.example.json config.json
python3 -m venv .venv-template
.venv-template/bin/python -m pip install -r requirements-detector.txt
```

Konfiguration zuerst prüfen: [CONFIG.md](CONFIG.md). Das Passwort ausschließlich lokal über `SDH_PASSWORD` bereitstellen, niemals im Repository speichern. Beispielsweise in einer interaktiven Bash ohne Passwort im Befehlsverlauf:

```bash
read -r -s -p 'SDH_PASSWORD: ' SDH_PASSWORD
printf '\n'
export SDH_PASSWORD
```

**Der folgende Start aktiviert sofort den automatischen Import inklusive Quellenlöschung. Nur nach den oben genannten Sicherheitsprüfungen ausführen:**

```sh
python3 app.py --host 127.0.0.1 --port 8787
```

`SDH_DATA` bestimmt das Datenverzeichnis; Standard ist `./data` relativ zum Projektverzeichnis, nicht zu einem beliebigen Arbeitsverzeichnis. Dort schreibt die Anwendung auch Datenbank, Analyseberichte und Statusdateien. Das Verzeichnis braucht Schreibrechte und gehört nicht ins Repository.

### Zugriffsschutz

HTTP Basic Auth prüft nur das Passwort; der Benutzername ist beliebig. Ein leeres/fehlendes `SDH_PASSWORD` verhindert den Serverstart. **Kein eingebautes TLS:** Basic Auth verschlüsselt keine Zugangsdaten. Loopback beibehalten und Zugriff nur über ein vertrauenswürdiges Netz, VPN oder einen abgesicherten TLS-Reverse-Proxy ermöglichen. Nicht direkt ins Internet stellen.

### Optional: systemd und Automount

[deploy/sd-video-hub.service.example](deploy/sd-video-hub.service.example) ist eine Vorlage für einen **systemd-Benutzerdienst**, mit Projekt unter `%h/apps/sd-video-hub`, Loopback-Bindung und verpflichtender lokaler `EnvironmentFile` unter `%h/.config/sd-video-hub/env`. Diese Datei außerhalb des Repositories selbst anlegen, nur für das Betriebskonto lesbar halten und dort `SDH_PASSWORD` setzen; optional auch `SDH_DATA`. Die Vorlage enthält bewusst keinen Passwortwert. Vor Aktivierung gilt dieselbe Löschwarnung wie beim manuellen Start.

Die zusätzlich vorhandenen Dateien `deploy/99-sd-video-hub.rules`, `deploy/sd-video-mount@.service` und `deploy/sd-video-mount` sind **optionale Root-Automount-Bausteine**, kein Installer. Die Regel ist breit und erfasst USB-Dateisystempartitionen, nicht nur eine bestimmte Karte. Das Skript setzt fest `uid=1000,gid=100`; Benutzer, Gruppe und Dateisystemunterstützung müssen vor einer manuellen Installation geprüft werden. Der Systemdienst erwartet das Skript unter `/usr/local/sbin/sd-video-mount`. Nichts davon wird automatisch installiert oder ausgeführt; keine sudo-/Skript-Autoausführung voraussetzen. Der Hub versucht unabhängig davon Mounts über `udisksctl`.

## Aufbau

| Datei/Pfad | Aufgabe |
| --- | --- |
| `app.py` | HTTP/Basic Auth, Geräte-Watcher, Kopieren/Prüfen/Löschen, Kompression, SQLite, Analyseansteuerung |
| `index.html` | Browseroberfläche und Player; vom Server mit Laufzeitwerten ergänzt |
| `hub_config.py`, `config.example.json` | Gemeinsame Konfiguration, Validierung und Video-Regeln |
| `hud_templates.py` | Frame-Sampling, zeitliche Bestätigung und Analyseberichte |
| `osd_matcher.py` | Gemeinsamer positionsunabhängiger Coarse-to-fine-Klassifikator |
| `flight_downloads.py` | Temporäre Nur-Flug-Exporte und Cleanup |
| `reanalyse_archive.py`, `refresh_one_template.py` | Manuelle Neuanalyse eines vorhandenen Archivs |
| `assets/*.mcm` | MAX7456-Schriftbänke für die Erkennung |
| `deploy/` | Dienstvorlage und optionale Automount-Bausteine |
| `data/` (nicht mitgeliefert) | `state.sqlite3`, `staging/`, `library/`, `thumbs/`, `template-reports/`, `flight-downloads/` und Laufzeitstatus |

Dieser Snapshot ist portabel: Die einzige Codeabweichung vom Live-Stand ist die serverseitige Übergabe des Archiv-Cutlist-Pfads statt eines hartcodierten Installationspfads. Die Beispielkonfiguration ist anonymisiert. Frühere PC-Einmalmigrationen, OCR-Experimente und Spikes sind bewusst nicht enthalten.

## Erkennung und Wartung

Verfahren, Grenzen, Parameter sowie manuelle Neuanalysebefehle für ein bereits initialisiertes Archiv: [DETECTION.md](DETECTION.md). Die Neuanalyse verändert Metadaten/Cutlists, nicht die archivierten Medien. Keine vollständige automatisierte Testsuite mitgeliefert; für diese Dokumentation wurden keine neuen Tests ausgeführt und weder Server noch Detektor gestartet.

## Schriften und Rechte

Mitgelieferte Schriften stammen aus Betaflight Configurator:

- [impact_mini.mcm](https://raw.githubusercontent.com/betaflight/betaflight-configurator/master/resources/osd/1/impact_mini.mcm)
- [clarity.mcm](https://raw.githubusercontent.com/betaflight/betaflight-configurator/master/resources/osd/1/clarity.mcm)
- [default.mcm](https://raw.githubusercontent.com/betaflight/betaflight-configurator/master/resources/osd/1/default.mcm)

Drittanbieterhinweise und separate Schriftlizenzen: [THIRD_PARTY.md](THIRD_PARTY.md). Diese Dokumentation vergibt keine Lizenz für den ursprünglichen Projektcode; Drittanbieter-Lizenzen sind davon getrennt zu betrachten.
