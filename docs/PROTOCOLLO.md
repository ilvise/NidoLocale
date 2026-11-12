# Protocollo BLE Nido (dall'APK com.nidospace 2.2.74, decompilato con Ghidra)

## Scansione
- Tappetino (seggiolino): nome che contiene `nido_` (senza distinzione maiuscole/minuscole)
- Cerotto termometro: nome che contiene `nidopatch` (dispositivo diverso)
- `cleanName`: se il nome contiene `_f0.`, l'ultimo pezzo dopo `_` viene tolto (versione del firmware)

## Connessione (BluetoothLeService)
- `connectGatt(autoConnect = true)`
- Dopo `discoverServices`, per ogni caratteristica il cui UUID è in:
  - `49535343-1E4D-4BD9-BA61-23C647249616` (TX del "Transparent UART" Microchip/ISSC)
  - `00010203-0405-0607-0809-0a0b0c0d2b10` (SPP Telink, server→client)

  l'app fa: `setCharacteristicNotification(true)` + `readCharacteristic` + scrittura di ENABLE_NOTIFICATION (01-00) su tutti i descrittori.
- **L'app non scrive mai comandi al dispositivo.** Nessuna chiave, nessun handshake.
- `00002a43-...` (Contact): solo per il cerotto termometro (thread che riabilita le notifiche ogni 10 s)

## Payload del tappetino (tipo 1): stringa ASCII
```
"$,<seduto>,<batteria_mV>"   oppure   "<seduto>,<batteria_mV>"
```
- si toglie `"$,"`, poi si divide su `,`
- `campo[0].trim() == "1"` → bambino seduto, altrimenti assente
- `campo[1]` = batteria in mV → percentuale:
  >=3102:100, >=3002:90, >=2902:80, >=2802:70, >=2702:60, >=2602:50, >=2502:40, >=2402:30, >=2302:20, altrimenti 0
- batteria < 21% → "Batteria in esaurimento"

## Logica dell'allarme
- Arriva una notifica → aggiorna `seduto` e lo stato passa a connesso (2)
- **Disconnessione GATT con `seduto == true` → startAlarm**: notifica, suoneria al volume massimo (STREAM_MUSIC), vibrazione, wakelock
- Notifica successiva dallo stesso dispositivo mentre l'allarme è attivo → stopAlarm
- Disconnessione con `seduto == false` → nessun allarme
- Il cloud (bindDevice.php, pushStatus, Firebase) serve solo per registrazione, abbinamento, statistiche, posizione GPS: **non è necessario per l'allarme**
