# Nido Locale

App Android alternativa per il tappetino anti-abbandono **Nido**, che funziona
**senza account, senza registrazione e senza cloud**. L'APK non ha nemmeno il permesso
INTERNET: tutto avviene tra il telefono e il tappetino via Bluetooth Low Energy.

> ⚠️ **Progetto non ufficiale.** Non è affiliato, approvato o supportato dal produttore
> del dispositivo Nido. "Nido" è usato solo per indicare con quale dispositivo l'app è compatibile.

> ⚠️ **Sicurezza.** È un software amatoriale, fornito **senza alcuna garanzia** (vedi
> [LICENSE](LICENSE)). Un dispositivo anti-abbandono protegge la vita di un bambino: prima di
> affidarti a questa app collaudala a lungo nelle tue condizioni reali (telefono, auto,
> abitudini) e non considerarla un sostituto dell'attenzione dell'adulto né dei sistemi
> certificati previsti dalla legge.

## Come funziona
- Un servizio in primo piano resta connesso al tappetino (nome `Nido_XXXX`).
- Il tappetino invia ogni ~2 s una stringa `"<seduto>,<batteria_mV>\n"` (es. `1,2900`).
  Dettagli del protocollo in [docs/PROTOCOLLO.md](docs/PROTOCOLLO.md).
- **Allarme** (suoneria sul canale sveglia al volume massimo, vibrazione, schermata a tutto
  schermo anche a telefono bloccato) se, con il bambino a bordo:
  - la connessione si perde (ti sei allontanato);
  - non arrivano dati per 15 s;
  - il Bluetooth viene spento;
  - il servizio viene riavviato e il tappetino non risponde entro 30 s.
- Il seggiolino risulta vuoto dopo 2 letture `0` consecutive (~4 s), per filtrare i movimenti.
- Tornando vicino al tappetino l'allarme si ferma da solo; altrimenti c'è il pulsante "Ho il bambino".
- Avviso di batteria del tappetino sotto il 20%.
- Pulsante "Prova allarme" per verificare che suoni.

## Installazione
1. Scarica l'APK dalle [Release](../../releases) (o compilalo, vedi sotto) e installalo.
2. Chiudi o disinstalla l'app ufficiale: il tappetino accetta un solo telefono connesso alla volta.
3. Concedi i permessi e risolvi gli avvisi arancioni nell'app (ottimizzazione batteria,
   allarme a schermo intero su Android 14+).
4. "Cerca tappetino" → tocca il tuo `Nido_XXXX` → "Prova allarme".

Requisiti: Android 8.0 (API 26) o superiore, con Bluetooth LE.

## Compilazione
Richiede JDK 17–21 e l'Android SDK (platform 34).
```
./gradlew assembleDebug testDebugUnitTest
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Licenza
[GPL-3.0](LICENSE)
