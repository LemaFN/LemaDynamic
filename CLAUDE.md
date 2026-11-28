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
- **Wackler/Ruck beim Ein-/Ausklappen (zweiter Fix gebaut, vom Nutzer zu bestätigen):** Video `lv_0_20261127122458` (langsam, mit Touch-Anzeige): Beim Antippen der Pille sprang sie ~17 % der Breite nach links; im ersten Video beim Einklappen nach rechts. Ursache: Das Overlay-Fenster änderte bei jedem Auf-/Zuklappen seine Größe (`updateViewLayout`), und das System zeigt dabei kurz den ALTEN Pufferinhalt am NEUEN Rahmen (Verschiebung = halbe Größenänderung, Wachsen = links, Schrumpfen = rechts). Fix in `IslandA11y.kt` (`applyWindow`): Das Fenster hat jetzt eine FESTE Größe (344 dp breit bzw. compactW, `EXP_MAX_H`=160 dp hoch, + Rand `M`), solange die Island sichtbar ist; es wird nur auf 1x1 geschrumpft, wenn nichts mehr angezeigt wird. Eingeklappt ist dieses große Fenster `FLAG_NOT_TOUCHABLE`; Tippen/Halten/Wischen übernimmt ein kleines unsichtbares Touch-Fenster (`touchWindow`, `addTouchWindow`) genau über der Pille. Ausgeklappt nimmt das große Fenster Touch an (Compose-Gesten in `Ui.kt`). Blur-Modus (`blur>0`) nutzt weiter das alte, eng anliegende Fenster (kann noch rucken). WICHTIG: ändert sich `dims()` in `Ui.kt` (max. Höhe), `EXP_MAX_H` mitändern.
- Davor: Drag-Deltas in Fensterkoordinaten (`Ui.kt`), gegen Sprünge während eines Wischens.
- Früher: dünner Strich beim Einklappen (Feder-Überschwingen), mit Mindestgröße 88 % der Zielgröße entschärft.
- **Uhr-App-Timer (Fix gebaut, vom Nutzer zu bestätigen):** Log vom Gerät: Paket `...alarmclock`, ongoing, ohne Kategorie/Template, Titel und Text LEER, also Zeit steckt in einer eigenen RemoteViews-Ansicht. `Notifs.kt` liest jetzt `contentView/bigContentView` per `RemoteViews.apply` aus (Chronometer + TextViews, `inspectViews`) und zeigt den Timer per `customTick()` (1-s-Tick). Das Log in der Diagnose zeigt für Uhr-Pakete zusätzlich `cv: chrono=… texts=…`. Pausiert-Erkennung nur per Reflection auf `Chronometer.mStarted` (kann auf Android 16 gesperrt sein, dann gilt "läuft").
- **Echte Lichtbrechung:** gelöst über Bildschirmfoto des AccessibilityService (siehe "In Arbeit"), läuft laut Nutzervideo. (Früher hier als "nicht möglich" vermerkt: überholt.)

### Zuletzt erledigt
- Absturz bei schnellem Auf-/Zuklappen (negative Höhe) behoben.
- Nachrichten: nur frische (< 60 s), neueste Chat-Nachricht, keine Re-Posts; Start-Meldung von Playern ("Musik-Player") wird nicht mehr als Nachricht gezeigt.
- Bluetooth-Verbindungen (Kind `bt`, Erlaubnis BLUETOOTH_CONNECT, Schalter `showBt`).

### In Arbeit: echte Lichtbrechung per Bildschirmfoto (Nutzerwunsch, Referenz: Telegram/iOS-Glas = weichgezeichneter Hintergrund + Tönung + helle Kante)
Idee: Der AccessibilityService darf per `takeScreenshot` (API 30+, `android:canTakeScreenshot="true"` in `res/xml/a11y.xml`) den Bildschirm lesen, ohne MediaProjection-Dialog. Schritte:
1. [erledigt, ungetestet] `Backdrop.kt` (Bitmap des Ausschnitts hinter dem Fenster, Skalierung 0.4), `grab()` in `IslandA11y.kt` (Foto beim ERSCHEINEN, solange das Fenster noch 1x1 ist; Ui wartet per `Backdrop.pending` max. 0,7 s), Schalter "Echter Hintergrund" (`Config.backdrop`), Diagnose-Zeile "Hintergrundfoto". Evtl. muss der Nutzer die Bedienungshilfe einmal aus-/einschalten, damit die neue Erlaubnis gilt.
2. [erledigt, ungetestet] AGSL-Shader (`GlassShader.kt`; `Glass.draw(... bgBmp, pos, refr)`, Regler `refrStrength`; bei Foto Deckkraft-Mischung `pow(opacity,1.7)`, Alpha 1): Hintergrund als `uniform shader bg` (BitmapShader), Weichzeichnen (mehrere Taps), Brechung entlang der Kante (SDF-Normale), Farbsäume, Tönung; Position der Island im Fenster per `onGloballyPositioned`.
3. [erledigt, ungetestet; Nutzer soll prüfen ob das Fenster beim App-Wechsel kurz flackert] Foto auch beim App-Wechsel erneuern (`refreshBackdrop()`, Fenster ~130 ms alpha 0) (Fenster dafür kurz alpha 0, damit sich die Island nicht selbst fotografiert).
4. [laufend] Nutzervideo 0.4.41 (ff5a87eb): Hintergrundfoto + Kantenbrechung FUNKTIONIEREN auf dem Gerät. Feinschliff: Weichzeichnen war punktiert (6 Taps) -> 13 Taps in 2 Ringen, größerer Radius, dunklerer Tint, Mischung pow(opacity,1.4). Feintuning (Stärke-Regler für Brechung/Blur), Fallback wenn kein Foto.

## Wer arbeitet woran
| Claude | Dateien / Thema | Seit |
|---|---|---|
| Claude A (ursprüngliche Sitzung) | Wackler-Bug: `Ui.kt` (Drag/Größen-Animation), `IslandA11y.kt` (Fenster) | 2026-11-27 |
| Claude B | (bitte eintragen) | |

## Log (neueste oben, eine Zeile pro Änderung)
- 2026-11-28 Claude A: Log vom Gerät: Uhr-TIMER OK (cv: chrono=true down=true, Tasten gefunden: Neu, Pausieren). STOPPUHR: Meldung "Stoppuhr | Zeitverhalten", cv: keine (keine eigene Ansicht, keine Zeit) -> neue Heuristik `stopwatchFromWhen` (n.when = Start). Log zeigt jetzt when/sub/info/keys für Uhr-Pakete. Falls Zeit falsch: Log prüfen.
- 2026-11-28 Claude A: Glas-Feinschliff: glatteres Weichzeichnen (13 Taps), dunklerer Tint, mehr Deckkraft-Gewicht.
- 2026-11-28 Claude A: Schritt 3 Hintergrundfoto wird beim App-Wechsel erneuert (refreshBackdrop).
- 2026-11-28 Claude A: Schritt 2 Shader mit Hintergrundfoto (Brechung, Farbsäume, Unschärfe), Regler "Stärke der Brechung".
- 2026-11-28 Claude A: Schritt 1 Bildschirmfoto-Grundlage (Backdrop.kt, grab(), a11y.xml canTakeScreenshot, Schalter, Diagnose).
- 2026-11-27 Claude A: Bluetooth bestätigt im Log ("BT Audio verbunden name=JBL Flip 5"); Anzeige klappt jetzt automatisch auf (Kind bt wie msg/call).
- 2026-11-27 Claude A: Stoppuhr der Uhr-App: Meldung hat Titel "Stoppuhr", Text "Zeitverhalten" (keine Zeit) -> Zeit steckt in RemoteViews; Ansichts-Auslese greift jetzt, wenn Titel/Text keine Zeit enthalten (nicht nur bei leerem Titel). Titel der Meldung wird als Name genutzt.
- 2026-11-27 Claude A: Mehrere Anzeigen gleichzeitig (Hub.sel/Hub.cycle: seitlich wischen oder Symbole im ausgeklappten Zustand, Punkte im eingeklappten); Uhr-Timer-Tasten (Pause/Weiter/Stopp/Löschen) aus den klickbaren Views der RemoteViews (InteractionHandler -> PendingIntent.send). Ungetestet.
- 2026-11-27 Claude A: Bluetooth: zusätzlich AudioDeviceCallback (ohne Erlaubnis) + A2DP/Headset-Broadcasts, alle Ereignisse im NLog ("BT ..."); Zeitformat mm:ss.cc für Stoppuhr; cv-Info im Log für alle Uhr-Meldungen. OFFEN: Stoppuhr der Uhr-App wird noch nicht angezeigt (Timer geht, bestätigt) - Nutzer soll Log nach Stoppuhr-Start schicken.
- 2026-11-27 Claude A: Wobble wieder voll (Dämpfung 0.9-0.55*wobble), Unterschwingen nur weich begrenzt (softFloor), Fenster mit mehr Rand (44/56 dp) gegen Abschneiden.
- 2026-11-27 Claude A: IslandA11y.kt: feste Fenstergröße + Touch-Fenster (gegen seitlichen Ruck), Ui.kt: EXP_MAX_H.
- 2026-11-27 Claude A: Notifs.kt: Uhr-Timer aus RemoteViews (Chronometer/Text), Log mit cv-Infos.
- 2026-11-27 Claude A: Ui.kt: Drag in Fenster-Koordinaten (Fix für seitlichen Ruck nach dem Einklappen).
- 2026-11-27 Claude A: CLAUDE.md angelegt (Übergabe-Datei).
