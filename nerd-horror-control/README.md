# Nerd Horror Control — Fabric Minecraft 26.3 (server-side)

**Status:** bronproject; nog NIET tegen Minecraft 26.3 gecompileerd of in-game getest. GitHub Actions bouwt de installeerbare JAR. Niet de broncode-ZIP uploaden naar de Minecraft-mods-map.

## Wat doet het?

Eén OP-only commandgroep: `/horror`. Werkt met vanilla mobs, vanilla geluiden, particles, titelteksten en potion effects. **Spelers hoeven geen mod of resourcepack te installeren.** Geen fullscreen afbeelding-jumpscare; de schrikacties gebruiken de mogelijkheden van vanilla Minecraft.

## Commands

Je voert de commands in Minecraft als OP uit. Bij het laatste argument vul je een online Minecraft-naam of `all` in.

| Command | Resultaat |
| --- | --- |
| `/horror spider Monkey01NL` | Een gigantische echte spin bij de speler; probeert aan te vallen |
| `/horror zombie Monkey01NL` | Een gigantische echte zombie bij de speler; probeert aan te vallen |
| `/horror skeleton Monkey01NL` | Een grote enge maar stilstaande skeleton zonder pijlschade |
| `/horror creeper Monkey01NL` | Grote stilstaande creeper, geen echte explosie |
| `/horror jumpscare Monkey01NL` | Titel op scherm, duisternis en schrikgeluid |
| `/horror darkness Monkey01NL` | Duisternis en grotten-geluid |
| `/horror thunder Monkey01NL` | Bliksemgeluid/partikels, geen echte blikseminslag |
| `/horror heartbeat Monkey01NL` | Herhaald hartslaggeluid |
| `/horror whisper Monkey01NL` | Enge fluistertekst en geluid |
| `/horror footsteps Monkey01NL` | Voetstappen rondom speler |
| `/horror fakecreeper Monkey01NL` | Sis en nepexplosie zonder blokschade |
| `/horror cavesounds Monkey01NL` | Enge grotgeluiden |
| `/horror blindness Monkey01NL` | Blindheid en schrikgeluid |
| `/horror eyes Monkey01NL` | Vreemde vlamdeeltjes en Enderman-geluid |
| `/horror config size 4` | Grootte van monsters (1–8) |
| `/horror config damage 1` | Melee-schade van spider en zombie (0–4 **HP**; 1 HP = een half hartje) |
| `/horror config duration 12` | Hoeveel seconden de horror-monsters blijven (3–60) |
| `/horror status` | Huidige instellingen bekijken |
| `/horror stop` | Lopende acties stoppen en horror-monsters verwijderen |
| `/horror help` | Commands bekijken |

`/horror spider all` werkt bij alle online spelers. Het maximum is 40 gelijktijdige horror-monsters. Creepers worden met NoAI gespawned en exploderen niet. Skeletons schieten niet. Zombies en spinnen gebruiken AI en kunnen **meerdere keren** aanvallen zolang zij leven; damage is per slag, niet per hele horroractie. Geen garantie over aanvalsgedrag in kleine ruimtes. Schade beïnvloed door armor, Minecraft-regels en andere mods.

## Installeerbare JAR bouwen zonder zelf Java/Gradle te installeren

1. Maak een **nieuwe lege GitHub-repository**, bijvoorbeeld `nerd-horror-control`.
2. Pak deze ZIP uit. Upload de inhoud, inclusief verborgen `.github/workflows/build.yml`, naar de nieuwe repo (of gebruik Codespaces en `git add .`, `git commit`, `git push`).
3. Open je repo in GitHub → **Actions** → `Build Nerd Horror 26.3` → **Run workflow**. Ook bij een push naar de `main`-branch wordt de build gestart.
4. Wacht tot de workflow groen is. Klik op het buildresultaat en download het artifact `nerd-horror-control-26.3`.
5. Pak de **artifact-ZIP** uit. Daarin staat `nerd-horror-control-1.0.0.jar`. Dit is pas de Minecraft-mod.
6. Maak een backup van je Minecraft-wereld en upload alleen die `.jar` naar **KaasHosting → mods**. Fabric API moet geïnstalleerd zijn (die heb je al). Herstart daarna je Minecraft-server.
7. Als OP: `/horror status` en test `/horror thunder <jouw-naam>`, daarna `/horror spider <jouw-naam>` met goedkoop materiaal.

Als de GitHub Actions-build rood wordt, is de JAR nog niet gebouwd. Kopieer dan de foutmelding uit de stap `Build Fabric mod`, en laat hem eerst herstellen voordat je iets uploadt. De mod is geschreven voor **Minecraft 26.3 + Fabric Loader 0.19.5 + Fabric API 0.161.0+26.3 + Java 25**.

### Configuratiebestand

Bij eerste `/horror config` wijziging maakt de mod automatisch `config/nerd-horror.properties` aan. Standaard grootte 4, schade 1 HP en duur 12 seconden. Geen spelersdownload nodig.

### Opletten

- Alleen Minecraft-OP's kunnen `/horror` gebruiken. Dit command is **geen Discord-slashcommand**.
- Mods als Flan/Gravestone kunnen interacties veranderen; test in een reservewereld.
- Grote monsters kunnen tegen muren vastlopen of anders reageren door vanilla-pathfinding.
- Geen eigen afbeelding/GUI op het beeldscherm van anderen zonder clientmod/resourcepack.
- Het originele projectskelet is vrij te bewerken en MIT-gelicentieerd.
