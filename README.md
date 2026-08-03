# Marine Radar (Android) — DRS4W-klient

En fristående Android-app (Kotlin + Jetpack Compose) som ansluter direkt
till en Furuno DRS4W ("1st Watch Wireless Radar") via dess eget WiFi-nät,
gör radar-discovery, tar emot spoke-data över UDP och ritar upp en
roterande PPI-radarbild. Ingen server eller Raspberry Pi behövs — allt
körs i appen.

## Nyheter: kontroller, bättre rendering, avsluta-knapp

- **Bitmap-baserad PPI-rendering** (`PpiRenderer.kt`) — snabbare och
  fixar tidigare visuella artefakter (den stora gröna "diamanten").
  Ritar nu en tunn radiell linje per spoke direkt in i en delad bitmap,
  istället för tusentals enskilda cirklar per frame.
- **Kommandokanal mot radarn** (`RadarCommandClient.kt`), porterad från
  `command.rs`/`report.rs`: login-handshake + riktiga kommandon för
  **Range (+/-), Gain, Sea clutter, Rain clutter och Standby/Transmit**,
  med Auto-lägen. Kontrollpanel längst ner på Radar-fliken.
  ⚠️ TCP-login-porten (antagen = 10000/`BASE_PORT`) är INTE bekräftad
  mot riktig hårdvara — bara själva handskaknings-BYTEFORMATET är
  verifierat. Justera `RadarCommandClient.LOGIN_PORT` om det visar sig
  fel mot din radar.
- **Emulatorn stödjer nu också kommandokanalen** — testa Range/Gain/
  Sea/Rain/Standby-Transmit helt lokalt innan du är vid radarn.
- **Avsluta-knapp** (✕) längst upp, stänger appen helt.

### Rättat: kommandon som inte gjorde något

Reglagen svarade inte alls tidigare. Loggen visade
`kunde inte skicka kommando: null` – det där tomma meddelandet är en
signatur för `NetworkOnMainThreadException`: Slider/Switch-callbacks i
Compose körs på huvudtråden, och att skriva till en socket direkt
därifrån är förbjudet på Android. Alla kommandoanrop körs nu på en
bakgrundstråd (`Dispatchers.IO`) via ViewModel.

Samtidigt fixades en relaterad robusthetsbugg: coroutine-cancellation
avbryter INTE blockerande socket-anrop (`ServerSocket.accept()`,
`DatagramSocket.receive()`). Om appen kopplade om (t.ex. efter ett
fel) kunde emulatorns gamla sockets bli kvar bundna till sina portar
och krocka med en ny anslutning ("login-server kraschade"). Alla
emulator-sockets stängs nu explicit i `stop()`, och både `connect()`
och `connectEmulator()` nollställer alltid en ev. gammal session
ordentligt innan en ny startas.

### Rättat: tomrum överst i radarbilden + zoom/helskärm

PPI-bilden visades tidigare i fel storlek/position med mycket
onödigt tomrum. Fixat genom att låta bilden alltid ta upp en perfekt
kvadrat centrerad i tillgängligt utrymme (`Modifier.aspectRatio(1f)`
istället för `fillMaxSize` + oklar `ContentScale`-hantering).

Nytt:
- **Pinch-to-zoom + panorering** direkt på radarbilden
- **Dubbeltryck** för att återställa zoom/position
- **Helskärmsknapp** (⤢ uppe till höger på radarbilden) — döljer
  flikar/kontrollpanel helt och visar bara radarbilden; tryck ⤡ för
  att gå tillbaka

 Appen har även en inbyggd **Felsökning**-flik med
liveloggning av all UDP-trafik, hex-dump per paket, portskanning och
delning/export av loggen.

## Bygg och installera – helt från telefonen (ingen dator behövs)

Jag kan inte skapa en färdig `.apk`-fil direkt här (ingen Android-
byggmiljö tillgänglig i det här verktyget), men projektet innehåller en
**GitHub Actions-workflow** (`.github/workflows/build-apk.yml`) som
bygger APK:n åt dig i molnet. Så här gör du:

1. **Skaffa ett gratis GitHub-konto** (om du inte redan har ett) –
   github.com, går bra i telefonens webbläsare eller GitHub-appen.
2. **Skapa ett nytt repo**, t.ex. `marine-radar-app`.
3. **Ladda upp projektfilerna.** Enklast sätt på telefon:
   - Installera **Termux** (från F-Droid, ingen root krävs).
   - Kör `termux-setup-storage` för att ge Termux tillgång till dina
     nedladdade filer.
   - Packa upp `MarineRadarApp.zip` (t.ex. med en filhanterare-app).
   - I Termux:
     ```
     pkg install git
     cd /sdcard/Download/MarineRadarApp   # sökväg dit du packade upp
     git init
     git add -A
     git commit -m "Initial commit"
     git branch -M main
     git remote add origin https://github.com/<ditt-användarnamn>/marine-radar-app.git
     git push -u origin main
     ```
     (Git frågar efter GitHub-inloggning – använd ett Personal Access
     Token, från github.com/settings/tokens, som lösenord istället för
     ditt vanliga lösenord.)
4. **Vänta på bygget.** Så fort du pushar startar GitHub Actions
   automatiskt. Gå till fliken **Actions** i ditt repo (i webbläsaren)
   och följ förloppet.
5. **Hämta APK:n.** När bygget är klart (grön bock), klicka in på
   körningen → scrolla ner till **Artifacts** → ladda ner
   `marine-radar-debug-apk`. Det blir en zip med `app-debug.apk` i.
6. **Installera på telefonen.** Öppna den nedladdade filen i
   Filhanteraren. Första gången ber Android dig tillåta installation
   från den appen (t.ex. webbläsaren eller Filer) under
   Inställningar → Appar → Särskild åtkomst → Installera okända appar.
   Installera sedan `app-debug.apk`.

Varje gång du ändrar koden och pushar igen (`git add -A && git commit
-m "..." && git push`) byggs en ny APK automatiskt.

## Så använder du appen

1. Öppna appen, tillåt de WiFi-/platsbehörigheter den ber om (krävs av
   Android för att kunna ansluta till ett specifikt WiFi-nät).
2. På fliken **Radar**: skriv in radarns WiFi-namn (SSID) och lösenord
   (finns på en etikett på DRS4W:n eller i installationsdokumenten) →
   **Anslut**.
3. Om PPI-bilden inte ser rätt ut (se avsnittet nedan om varför), gå
   till fliken **Felsökning** för att se vad som faktiskt kommer in.

## Felsöknings-fliken

- **Live-logg**: varje UDP-paket appen skickar/tar emot listas med
  tid, avsändare/mottagare, port och längd.
- Tryck på en rad för att se **hex-dump + ASCII-tolkning** av
  paketets innehåll.
- **Skanna portar**: skickar samma discovery-fråga som radarn
  förväntar sig till en lista vanliga marina UDP-portar och lyssnar på
  var och en – bra för att hitta radarns *riktiga* portar om
  standardgissningarna i koden inte stämmer.
- **Dela logg**: exporterar hela loggen som en textfil du kan skicka
  till dig själv (mejl, molnlagring, etc.) för vidare analys, eller
  klistra in här i chatten om du vill ha hjälp att tolka den.

## Vad som redan är klart

- **WiFi-anslutning** (`network/RadarWifiManager.kt`)
- **UDP-discovery + spoke-lyssnare** (`network/RadarUdpClient.kt`)
- **Portskanner** (`network/RadarPortScanner.kt`)
- **Paketlogg** (`debug/PacketLogger.kt`)
- **Spoke-avkodning** (`radar/SpokeDecoder.kt`)
- **PPI-rendering** (`ui/PpiView.kt`)
- **Felsökningsvy** (`ui/DebugScreen.kt`)
- **UI/state** (`MainActivity.kt`, `radar/RadarViewModel.kt`)

## Vad som INTE är verifierat mot riktig hårdvara

Furunos exakta binärformat för DRS4W:s UDP-paket (portnummer, header-
layout, hur echo-datan är komprimerad) är inte officiellt publicerat.
Koden innehåller en **rimlig placeholder** (se `TODO`-kommentarer i
`RadarUdpClient.kt` och `SpokeDecoder.kt`).

### Så här färdigställer du protokollet – med hjälp av Felsöknings-fliken

1. Anslut till radarns WiFi och tryck **Skanna portar** i appen. Om
   radarn svarar på någon port loggas det direkt – kolla hex-dumpen.
2. Jämför det du ser med öppna källkods-projektet
   github.com/MarineYachtRadar/mayara-server — det har en fungerande,
   hårdvarutestad implementation av DRS4W-protokollet i Rust (`src/`).
   Använd det som facit för exakta portnummer, header-fält och
   paketuppackning.
3. Uppdatera konstanterna i `RadarProtocolConstants` och logiken i
   `SpokeDecoder.decode()` därefter, committa, pusha — ny APK byggs
   automatiskt.
4. Testa igen mot din riktiga radar och jämför PPI-bilden med den
   officiella iOS-appen.

## Nytt: Vad vi lärt oss från Mayaras källkod

Mayara-servern definierar alla exakta multicast-adresser/portar per
märke i filen `src/lib/brand/furuno/protocol.rs` i deras repo (bekräftat
via deras egen `docs/capturing-traffic.md`), men den filens exakta
innehåll har jag inte kunnat läsa direkt här. Det vi däremot vet
säkert från deras dokumentation:

- **Radarn sänder discovery-beacons proaktivt** – till skillnad från
  vår tidigare modell (skicka en `$N96`-fråga och vänta på svar)
  verkar Furuno-radarn prata av sig själv, kontinuerligt, utan att bli
  tillfrågad. Det är troligen därför vår aktiva fråga inte fick svar.
- **Både broadcast OCH multicast används.** Vår ursprungliga kod
  lyssnade bara på broadcast.
- Radarn kan landa i `172.31.0.0/16` (bekräftat, Furuno-specifikt) —
  det stämmer med vad du redan ser i loggen (WiFi ansluter fint).

### Två skanningsverktyg i appen nu

**Aktiv skanning** (som innan) – skickar `$N96`-frågan till en lista
kandidatportar och väntar på svar.

**Passiv skanning (ny!)** – lyssnar i 20 sekunder på ett brett spann av
portar OCH på flera kandidat-multicast-grupper *samtidigt*, utan att
skicka något alls. Eftersom radarn enligt Mayara pratar av sig själv är
detta troligen den mer lovande vägen. Kör den, vänta 20 sekunder, och
kolla sedan **Paket**-loggen för vad som kom in.

### Om ingetdera ger napp

1. **Testa längre passiv skanning** – radarns beacon-intervall kan vara
   längre än 20s. Säg till om du vill att jag höjer standardtiden.
2. **Hämta `protocol.rs` själv** – om du (eller en vän) har tillgång
   till en dator: `git clone https://github.com/MarineYachtRadar/mayara-server`
   och öppna `src/lib/brand/furuno/protocol.rs` — där står exakt adress
   och port som facit. Klistra in innehållet här i chatten så
   uppdaterar jag appen med de exakta värdena direkt.
3. **Kör själva Mayara-servern** en gång (även utan Raspberry Pi – den
   funkar på Windows/macOS/Linux, se ENDUSER.md i deras repo) mot din
   radar från en vanlig dator som tillfälligt ansluter till DRS4W:s
   WiFi. Terminalutskriften när den upptäcker radarn avslöjar troligen
   exakt adress/port i klartext.
4. **Wireshark-fångst** enligt deras `docs/capturing-traffic.md` – kräver
   en WiFi-adapter i monitor-läge, mer omständligt men helt definitivt.

## Status: verifierat protokoll inlagt (från riktig källkod)

Tack vare att du klonade och delade `protocol.rs`, `command.rs`, `mod.rs`
och `settings.rs` från mayara-server har vi nu **verifierade, exakta**
protokolldetaljer istället för gissningar:

- **Discovery är ett rått binärt protokoll**, INTE NMEA-text som vi
  först trodde. Tre fasta binära paket skickas som UDP-broadcast till
  `172.31.255.255:10010` (`REQUEST_BEACON_PACKET`, `REQUEST_MODEL_PACKET`,
  `ANNOUNCE_CLIENT_PACKET`), och radarn svarar med en 170-byte
  modell-rapport som innehåller modellnamn + serienummer i klartext.
- **Spoke-data** går på port `10024`, antingen via multicast
  (`239.255.0.2`) eller broadcast (`172.31.255.255`) — appen lyssnar nu
  på båda samtidigt.
- **Spoke-frame-headern** (16 byte) är helt kartlagd bitvis (se
  `SpokeDecoder.kt`) — vi validerar `packet_type == 0x02` och läser ut
  metadata (range, encoding-läge, spoke-count) korrekt.

Allt detta är implementerat i appen nu (`FurunoProtocol.kt`,
`RadarUdpClient.kt`, `SpokeDecoder.kt`).

### Vad som FORTFARANDE saknas

~~Den exakta algoritmen för att packa upp pixeldatan~~ **UPPDATERING:
klar!** Du delade `report.rs`, och nu är den fullständiga, verifierade
avkodningsalgoritmen porterad rakt av till Kotlin
(`SpokeDecoder.kt` → klassen `FurunoSpokeDecoder`):

- Alla fyra encoding-lägen (0/1/2/3), inklusive delta-kodning mot
  föregående spoke för lägen 2/3
- Korrekt header-parsning (sweep-count, sweep-len, vinkel, range)
- Samma "stretch"-logik och gammakurva för lågeffekt-radarer (DRS4W)
  som originalet, för att svaga ekon syns tydligt

**Medvetna förenklingar** för v1 (kan läggas till senare om det behövs):
- Ingen Tile-format (bara NXT-modeller använder det, inte DRS4W)
- Ingen dual-range (DRS4W har bara en range)
- Ingen Doppler/Target Analyzer-färgläggning (DRS4W saknar funktionen)
- Enklare display-mappning istället för hela legend/palette-systemet

### Testa det vi har nu

Just nu bör hela kedjan fungera: discovery → hitta radarn → ta emot
spoke-data → avkoda → rita PPI-bild. Anslut igen och se om du faktiskt
får upp en radarbild! Om något fortfarande är fel, skicka gärna:
- **Felsökning → Applogg** (visar `SpokeDecoder: frame #...`-rader med
  sweepCount/encoding/range för varje mottagen frame — superanvändbart
  för att se om vi tolkar rätt)
- **Felsökning → Paket** exporterad som text

Om du vill gräva ännu djupare (t.ex. lägga till styrning av gain/range
från appen, eller riktig ARPA-målspårning) kan `command.rs` och
`settings.rs` (som du redan delat) användas för det i nästa steg.

## Nytt: trolig grundorsak hittad + emulator + samlad logg-export

Din senaste felsökningsrunda visade att **även 20 sekunders passiv
lyssning på 41 portar hörde noll paket** – trots att våra egna
discovery-paket bevisligen skickas helt korrekt (bekräftat i dina
skärmdumpar, byte-för-byte rätt). Det pekar starkt mot att paketen
aldrig når radarn: sannolikt skickade vi till fel **broadcast-adress**
eftersom vi antog `/16`-nätmask (`172.31.255.255`), men telefonens
faktiska DHCP-lease på radarns nät kan mycket väl ha en annan mask
(t.ex. `/24`), vilket gör den adressen fel för just det nätet.

**Fixat:**
- `NetworkDiagnostics.kt` (ny) läser ut telefonens FAKTISKA lokala
  IP + nätmask på radarns WiFi och räknar ut rätt riktad
  broadcast-adress för den – loggas nu tydligt i Applogg direkt vid
  anslutning (`NetworkDiagnostics: lokal IP=... → beräknad broadcast=...`).
- Discovery skickar nu till **flera** broadcast-mål samtidigt: den
  beräknade rätta adressen, `172.31.255.255` (gamla antagandet) och
  `255.255.255.255` ("limited broadcast", kräver ingen känd nätmask
  alls och bör alltid nå det lokala nätet oavsett).
- Samma fix i portskannern.

**Testa igen och kolla särskilt** raden `NetworkDiagnostics: lokal
IP=...` i Applogg – om nätmasken visar sig vara t.ex. `/24` har vi
bekräftat grundorsaken.

### Emulatorläge (testa utan att vara nära radarn)

Ny knapp på anslutningsskärmen: **"🧪 Testa med emulator"**. Den
startar en lokal simulator (`FurunoRadarEmulator.kt`) som pratar
EXAKT samma binära protokoll som en riktig DRS4W, men över
loopback (127.0.0.1) – ingen WiFi eller radar behövs. Den svarar på
discovery precis som riktig hårdvara (modellnamn "DRS4W-EMU" så det
syns att det är simulerat) och strömmar syntetiska spoke-frames
(kodade med samma RLE-format som riktiga radarn) med en roterande
"target"-blip och en fast "kustlinje"-båge, så PPI-bilden faktiskt
visar något.

Det här testar HELA kedjan – discovery, UDP-mottagning, header-
parsning, RLE-avkodning, PPI-rendering – utom själva
radiosändningen/WiFi-delen. Om emulatorn ger en fin roterande bild
men den riktiga radarn inte gör det, vet vi att felet sitter i
nätverks-/anslutningslagret (broadcast/mask-problemet ovan är en
stark kandidat), inte i avkodningslogiken.

### Samlad logg-export

Ny knapp överst i Felsökning: **"📄 Exportera ALLT"**. Slår ihop
applogg (alla sessioner) + paketlogg (nuvarande + tidigare sparad) +
ev. kraschrapport + enhetsinfo till EN textfil och öppnar delnings-
dialogen – välj t.ex. "Spara till Filer" för att ladda ner den, eller
dela direkt hit i chatten.

## Nytt: kartöverlägg (Google Maps eller OpenStreetMap)

Ny 🗺️-brytare i statusraden på Radar-fliken slår på ett kartöverlägg —
radarbilden visas då korrekt positionerad och roterad ovanpå en
riktig karta, utifrån telefonens GPS-position och kompasskurs (DRS4W
själv skickar ingen position — det är bara en radarsensor, positionen
måste komma från telefonen).

**Två valbara kartleverantörer** (växla med knapparna överst på kartan):

- **OpenStreetMap** (standardval) — **helt gratis, ingen API-nyckel
  behövs, fungerar direkt.** Implementerat via `osmdroid`.
- **Google Maps** — kräver en gratis API-nyckel (se nedan), men har
  bl.a. satellitvy.

### Skaffa en Google Maps API-nyckel (valfritt, bara om du vill använda den)

1. Gå till [Google Cloud Console](https://console.cloud.google.com/)
   → skapa ett nytt projekt (gratis)
2. Sök upp **"Maps SDK for Android"** under APIs & Services → Library
   → tryck **Enable**
3. Gå till **APIs & Services → Credentials** → **Create credentials →
   API key**
4. (Rekommenderat) Begränsa nyckeln till **Android-appar** och lägg in
   ditt paketnamn (`com.example.marineradar`) + SHA-1-fingeravtryck
5. Kopiera nyckeln

**Lägg in nyckeln (välj ett sätt):**
- **GitHub Actions (rekommenderat, för din vanliga bygg-väg):** gå till
  ditt repo → **Settings → Secrets and variables → Actions → New
  repository secret** → namn `MAPS_API_KEY`, värde = din nyckel.
  Workflowen skickar redan med den automatiskt vid varje bygge.
- **Lokalt i Android Studio:** lägg till raden
  `MAPS_API_KEY=din-nyckel-här` i `local.properties` (skapas automatiskt
  av Android Studio, redan gitignorad).

Saknas nyckeln byggs appen ändå fint — Google Maps-kartan visas bara
tom/grå. OpenStreetMap kräver som sagt ingen nyckel alls.

### Hur det fungerar tekniskt

- **Positionering**: `BoatLocationProvider.kt` läser telefonens GPS
  (`LocationManager`) + kompass (`TYPE_ROTATION_VECTOR`-sensorn).
- **Google Maps**: radarbilden ritas via maps-composes `GroundOverlay`
  – en inbyggd funktion för just det här (bild + position + storlek i
  meter + rotation).
- **OpenStreetMap**: osmdroid har ingen inbyggd motsvarighet till
  GroundOverlay, så `OsmRadarMapView.kt` implementerar det manuellt via
  ett eget `Overlay` som räknar ut skärmposition/skala från kartans
  `Projection` varje ritning.
- Båda är oberoende av vald kartleverantör och kan enkelt bytas ut om
  du vill lägga till fler (t.ex. MapLibre/OpenFreeMap) — se
  `MapProviderType.kt` och `RadarMapContainer.kt`.

## Nätverksdetaljer (bekräftade från Furunos dokumentation)

- Subnät: `172.31.0.0/16`
- DRS4W skapar ett eget WiFi-nät (SSID/lösenord står på radarns etikett)
- Flera klienter kan vara anslutna samtidigt — appen kan alltså köras
  parallellt med den officiella iOS-appen under testning

## Nästa steg / förbättringar


- Lägg till kontroller för sändning (STBY/TX), range, gain, sea/rain
  clutter.
- Spara/rita ARPA-mål om radarn stödjer det.
- Rotera bilden efter kompasskurs (kräver GPS/kompass-integration).
