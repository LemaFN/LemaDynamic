# Übergabe zwischen Claude-Instanzen (HyperIsland / LemaDynamic)

Der Nutzer ist kein Programmierer und arbeitet vom Handy. Mehrere Claudes arbeiten parallel in diesem Repo.
**Diese Datei zuerst lesen und vor jedem Push aktualisieren** (Abschnitt "Aktueller Stand" + "Log").

## Arbeitsregeln (damit wir uns nicht in die Quere kommen)
1. Vor dem Arbeiten: `git pull --rebase origin main`. Direkt vor jedem Push nochmals.
2. Kleine Commits, jeder Commit gleich nach `main` pushen (Nutzerwunsch: jede Codeänderung sofort auf GitHub).
3. Vor dem Bearbeiten einer Datei in "Wer arbeitet woran" eintragen, woran du arbeitest. Wenn dort eine andere Datei-Gruppe steht: nicht anfassen, sondern hier eine Notiz hinterlassen.
4. Kein lokaler Compiler: Build läuft in GitHub Actions. Nach dem Push prüfen: `gh run list -R LemaFN/LemaDynamic --limit 1`, bei Fehler `gh run view <id> --log-failed`. Ein Fehler "Could not find ... kotlin-reflect" o. ä. ist ein Netzwerk-Flake: `gh run rerun <id>`.
5. Neuer Build bricht laufende ab (cancel-in-progress). Versionsnummer = 0.4.<Run-Nummer>, sichtbar unten in der App.
6. Die APK holt der Nutzer unter Actions → neuester Lauf → Artifacts → HyperIsland-debug-apk. Keine Tests auf dem Gerät möglich, ehrlich sagen, was ungetestet ist.
7. Antworten an den Nutzer auf Deutsch, kurz, ohne Fachjargon.

## Projekt
Android-App (Kotlin + Jetpack Compose), "Dynamic Island" für Android (Gerät des Nutzers: nubia NX809J, Android 16, 144 Hz).
- `IslandA11y.kt`: AccessibilityService, Overlay-Fenster (TYPE_ACCESSIBILITY_OVERLAY) mit ComposeView; Fensterposition/-größe (`place()`, Collector mit `updateViewLayout`), Fly-In-Icon, Lade- und Bluetooth-Receiver.
- `Notifs.kt`: NotificationListener (Medien, Timer, Nachrichten, Anrufe). `Diag.kt`: Log der letzten Benachrichtigungen.
- `Hub.kt`: Zustand (acts, exp, pulls, hidden). `Cfg.kt`: Einstellungen (SharedPreferences, live).
- `Ui.kt`: Island-Compose-UI (Größen-Animation per Feder in `animSize`, Gesten, Glas). `GlassShader.kt`: AGSL-Shader.
- `MainActivity.kt`: Einstellungen/Testen/Diagnose. `App.kt`: Crash-Handler.
- `backup/hyper-island.html` ist die erste HTML-Idee, bleibt als Backup unverändert.

## Aktueller Stand (zuletzt Build 0.4.x, grün)
Fertig: Glas-Look mit Shader, Musik/Timer/Nachrichten/Anrufe/Laden/Bluetooth, Einziehen minimierter Apps, viele Einstellungen, Diagnose-Karte.

### Offene Bugs
- **Wackler/Ruck beim Einklappen (Fix in Build nach 0.4.x, noch vom Nutzer zu bestätigen):** Ursache laut Video `Screenrecorder-20261127-072130` (ca. Sekunde 3): Die Pille sprang ~90 dp zur Seite, war halbtransparent und federte in ~100 ms zurück. Das ist der Drag-Offset `ox` in `Ui.kt` (Alpha hängt an |ox|, Rückfeder `spring(0.6, 500)`). Grund: Die Drag-Deltas wurden in lokalen Koordinaten der Island gemessen; klappt die Island unter dem Finger ein (Breite 344 → 236, zentriert), springt das lokale Koordinatensystem. Fix: Drag wird jetzt aus Fenster-Koordinaten (`localToWindow`) berechnet, absolut statt aufsummiert. Falls es weiter ruckelt: Hypothesen (a) `updateViewLayout` bei Fenster-Verkleinerung, (b) Musik-Aktivität kurz entfernt/neu gesetzt.
- Früher: dünner Strich beim Einklappen (Feder-Überschwingen), mit Mindestgröße 88 % der Zielgröße entschärft.
- **Uhr-App-Timer wird nicht angezeigt:** Format der Uhr-Benachrichtigung unbekannt. Heuristik in `Notifs.kt` ist eingebaut; der Nutzer soll in der App unter Diagnose "Log kopieren" und den Text schicken, dann gezielt anpassen.
- **Echte Lichtbrechung des Hintergrunds** nicht möglich (eine App kann den Hintergrund hinter ihrem Fenster nicht lesen). Nur Rand-Effekte per Shader. MediaProjection wäre die einzige Alternative (Akku, Dauer-Hinweis), vom Nutzer nicht gewählt.

### Zuletzt erledigt
- Absturz bei schnellem Auf-/Zuklappen (negative Höhe) behoben.
- Nachrichten: nur frische (< 60 s), neueste Chat-Nachricht, keine Re-Posts; Start-Meldung von Playern ("Musik-Player") wird nicht mehr als Nachricht gezeigt.
- Bluetooth-Verbindungen (Kind `bt`, Erlaubnis BLUETOOTH_CONNECT, Schalter `showBt`).

## Wer arbeitet woran
| Claude | Dateien / Thema | Seit |
|---|---|---|
| Claude A (ursprüngliche Sitzung) | Wackler-Bug: `Ui.kt` (Drag/Größen-Animation), `IslandA11y.kt` (Fenster) | 2026-11-27 |
| Claude B | (bitte eintragen) | |

## Log (neueste oben, eine Zeile pro Änderung)
- 2026-11-27 Claude A: Ui.kt: Drag in Fenster-Koordinaten (Fix für seitlichen Ruck nach dem Einklappen).
- 2026-11-27 Claude A: CLAUDE.md angelegt (Übergabe-Datei).
