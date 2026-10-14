# Nerd Max One Totem — Fabric 26.3

Server-side Fabric mod for Minecraft 26.3.

## Wat doet deze mod?

- Heeft een speler **geen** Totem of Undying: pickup werkt normaal.
- Heeft een speler **al 1** Totem of Undying: een tweede totem wordt **niet opgepakt**.
- De tweede totem blijft gewoon op de grond liggen.
- Zodra de bestaande totem is gebruikt, weggegooid of anders uit de inventory verdwijnt, kan de speler weer een totem oppakken.
- Werkt ook wanneer de eerste totem in de offhand zit.
- De raid/drop mechanics worden niet aangepast.

## Server

Deze mod is bedoeld als server-side mod. Zet de gebouwde `.jar` in de `mods` map van je Fabric 26.3 server.

Vereisten:
- Minecraft 26.3
- Fabric Loader 0.19.5+
- Java 25

## Build

Gebruik Gradle 9.6+ en JDK 25:

```bash
gradle build
```

De jar staat daarna in:

```text
build/libs/nerd-max-one-totem-1.0.0.jar
```
