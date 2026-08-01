# Marine Radar (Android) — DRS4W-klient

En fristående Android-app (Kotlin + Jetpack Compose) som ansluter direkt
till en Furuno DRS4W ("1st Watch Wireless Radar") via dess eget WiFi-nät,
gör radar-discovery, tar emot spoke-data över UDP och ritar upp en
roterande PPI-radarbild. Ingen server eller Raspberry Pi behövs — allt
körs i appen. Appen har även en inbyggd **Felsökning**-flik med
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
