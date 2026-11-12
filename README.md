# Nido Locale

> [!CAUTION]
> ## ⛔ LEGGERE PRIMA DI USARE: NESSUNA GARANZIA, NESSUNA RESPONSABILITÀ
>
> **Questo è un progetto personale, sperimentale e amatoriale.** È stato scritto per uso
> privato dell'autore e viene pubblicato solo a titolo informativo e di studio.
>
> - **NON è un dispositivo di sicurezza certificato** e **NON è conforme** ad alcuna norma sui
>   dispositivi anti-abbandono (in Italia: art. 172 del Codice della Strada e D.M. 2 ottobre 2018 n. 122).
>   Usarla al posto dell'app ufficiale **può far venire meno la conformità** del tuo sistema
>   anti-abbandono ai requisiti di legge.
> - **PUÒ NON FUNZIONARE.** Bug, aggiornamenti di Android, ottimizzazioni della batteria del
>   produttore del telefono, Bluetooth spento o disturbato, telefono scarico, silenzioso o
>   lontano, firmware diverso del tappetino, e infinite altre cause possono impedire
>   all'allarme di suonare **senza alcun preavviso**.
> - **L'AUTORE NON SI ASSUME ALCUNA RESPONSABILITÀ**, di nessun tipo, per qualsiasi danno a
>   persone, animali o cose derivante dall'uso, dal mancato funzionamento o dal funzionamento
>   errato di questo software, **compresi lesioni o morte di bambini o di chiunque altro**.
>   L'autore non risponde in alcun modo della sicurezza dei figli altrui.
> - Il software è fornito **"COSÌ COM'È", SENZA GARANZIE DI ALCUN TIPO**, esplicite o
>   implicite, comprese quelle di funzionamento, affidabilità o idoneità a uno scopo
>   particolare, nei limiti massimi consentiti dalla legge (vedi le sezioni 15 e 16 della
>   [licenza GPL-3.0](LICENSE)).
> - **Nessun software sostituisce l'attenzione dell'adulto.** La responsabilità della
>   sorveglianza del bambino resta **sempre e soltanto di chi lo trasporta**.
>
> **Se scarichi, compili, installi o usi questa app, accetti di farlo interamente a tuo
> rischio e sotto la tua esclusiva responsabilità.** Se non accetti queste condizioni, non
> usarla e continua a usare l'app ufficiale del produttore.

> [!WARNING]
> **Progetto non ufficiale.** Non è affiliato, approvato o supportato dal produttore del
> dispositivo Nido. Il nome "Nido" è usato solo per indicare con quale dispositivo l'app è
> compatibile. Il protocollo è stato ricostruito a scopo di interoperabilità e potrebbe
> essere incompleto o cambiare con nuovi firmware.

App Android alternativa per il tappetino anti-abbandono **Nido**, che funziona
**senza account, senza registrazione e senza cloud**. L'APK non ha nemmeno il permesso
INTERNET: tutto avviene tra il telefono e il tappetino via Bluetooth Low Energy.

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
