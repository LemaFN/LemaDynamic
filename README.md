# Hyper Island (Android)

Dynamic Island mit Glas-Look für Android – Kotlin + Jetpack Compose.

## Ordnerstruktur
```
app/                      Android-App (Kotlin, Jetpack Compose)
  src/main/java/de/lema/hyperisland/   Quellcode
  src/main/res/           Ressourcen
.github/workflows/        GitHub Actions: baut bei jedem Push die APK
backup/hyper-island.html  Erste HTML-Version (nur als Backup)
```

## APK bekommen
GitHub → Reiter **Actions** → letzter Lauf „Build APK“ → unten bei **Artifacts** `HyperIsland-debug-apk` herunterladen.

## Einrichten
1. App öffnen
2. "Bedienungshilfe Hyper Island" aktivieren (Einstellungen > Bedienungshilfen > Heruntergeladene Apps)
3. "Benachrichtigungszugriff" erlauben
4. Ausgegraut? Einstellungen > Apps > Hyper Island > Drei Punkte > Eingeschränkte Einstellungen zulassen
5. Xiaomi/HyperOS: Autostart erlauben, Akku auf "Keine Einschränkungen"

## Bedienung
Tippen = auf/zu, lang drücken = Details, wischen = schließen, Cover/Icon antippen = App öffnen.
Beim Minimieren einer App mit laufender Musik/Anruf zieht sich die Island ein.
