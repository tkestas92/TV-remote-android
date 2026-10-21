# TV Remote

## Security

- The pairing certificate and the ADB key stay in app-private storage (`remote-identity`, `adb-key`). Backups are off (`android:allowBackup="false"`), and the backup rules also exclude those directories and the DataStore file that holds launcher tokens.
- After a successful pairing, the TV's TLS certificate is pinned per host. If the TV presents a different certificate, the connection stops with "TV certificate changed — pair again". Pairing again replaces the pin. Clearing the paired host removes it.
- The launcher API is plain HTTP. Android cannot limit cleartext to raw IP addresses, so the app allows it only after checking the address is on a private LAN (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16). The same check applies to the remote protocol and ADB.
- The ADB key grants control of the TV. Keep the phone locked.
