# Comandi Linux utilizzati da AndroidCamera

## Gestione v4l2loopback (slot virtuali per webcam USB Device)

### Installazione delle dipendenze

`install-deps.sh` installa i pacchetti di sistema necessari per AndroidCamera
(`v4l2loopback-dkms`, `v4l2loopback-utils`, `ffmpeg`, `curl`, `python3`,
`python3-tk`, `adb`, `linux-headers-$(uname -r)`).

**Esempio:**
```bash
sudo ./install-deps.sh
```

Questo script installa **solo** i pacchetti: NON carica v4l2loopback e NON
configura alcun caricamento automatico all'avvio del SO. Il modulo viene
caricato da `run.sh` all'avvio della GUI (vedi sotto).

### Caricamento degli slot all'avvio della GUI

`run-linux-gui.sh` (che richiama `AndroidCameraLinuxGui/run.sh`) carica il
modulo `v4l2loopback` con **almeno** `V4L2_SLOTS` slot (letto dal file
`.env` della repo root) **prima** di far partire la GUI Python.

Caricamento: `modprobe v4l2loopback devices=N exclusive_caps=0 max_buffers=8`
con `N = V4L2_SLOTS`. Gli indici `/dev/videoN` sono scelti dal kernel
(auto-assignment): non vengono pinnati da `run.sh`, perché su alcuni dkms
l'array `video_nr` ha dimensione fissa (qui 8) e passare una lista più
lunga di 8 fa fallire `modprobe` con `EINVAL`. Per lo stesso motivo
`V4L2_SLOTS` non ha un upper bound forzato qui; se superi il limite del
modulo, `run.sh` blocca con un errore chiaro ("loaded only K slot(s) of N
requested").

Comportamento di `run.sh`:

- se `v4l2loopback` è già caricato con almeno `V4L2_SLOTS` slot nostri →
  non fa nulla (nessuna richiesta di password, avvio immediato);
- se il modulo non è caricato → esegue `modprobe v4l2loopback devices=N`;
- se il modulo è caricato con un numero di slot **minore** di N → lo
  scarica (`modprobe -r`) e lo ricarica con N slot;
- se lo scaricamento fallisce (un consumer — tipicamente OBS, VLC o un
  browser — tiene aperto un `/dev/videoN`) → **blocca** l'esecuzione con un
  errore, perché senza gli slot necessari la GUI non può funzionare.

Il count degli slot nostri viene fatto leggendo
`/sys/class/video4linux/videoN/device/driver`: conta solo i nodi il cui
driver è `v4l2loopback`, così le webcam reali (`uvcvideo` & simili) non
vengono erroneamente incluse.

Per ottenere i privilegi di root necessari a `modprobe`, `run.sh` richiede il
ticket sudo in anticipo (`sudo -v`): se non esiste già un ticket sudo valido,
chiede la password una volta sola all'inizio, poi riusa il ticket per i
`modprobe` successivi. In alternativa si può lanciare `sudo ./run-linux-gui.sh`.

**Esempio:**
```bash
./run-linux-gui.sh
# (oppure, per avere già i privilegi:)
sudo ./run-linux-gui.sh
```

### Modifica del numero di slot

Il numero di slot `v4l2loopback` è definito in `.env` con la variabile
`V4L2_SLOTS` (intero >= 1; default: 10; consigliato >= 10 per avere margine
per più telefoni + cambi di risoluzione). Valori più alti del limite del
modulo installato (su alcuni dkms l'array `video_nr` è fisso a 8) possono
far fallire `modprobe`: tieni `V4L2_SLOTS` <= 8 se il tuo modulo espone
solo 8 entry in `parameters/video_nr`. Dopo aver modificato il valore:

1. chiudi OBS/VLC e gli altri programmi che usano una webcam
   (altrimenti `run.sh` non potrà scaricare `v4l2loopback`);
2. rilancia `./run-linux-gui.sh`.

Non è necessario riavviare il computer: gli slot vengono ricreati ad ogni
avvio della GUI da `run.sh`.

### Comandi di supporto

```bash
# Controllare i dispositivi video disponibili
ls -l /dev/video*

# Elencare i dispositivi v4l2loopback
v4l2-ctl --list-devices

# Contare gli slot v4l2loopback ATTIVI (metodo affidabile, indipendente dal
# dkms: conta i nodi videoN il cui driver è v4l2loopback)
for e in /sys/class/video4linux/video*; do
  drv=$(readlink -f "$e/device/driver" 2>/dev/null); drv=${drv##*/}
  [[ "$drv" == "v4l2loopback" ]] && echo "$e -> v4l2loopback"
done

# Su alcuni dkms è disponibile anche il parametro devices (non sempre esiste)
cat /sys/module/v4l2loopback/parameters/devices 2>/dev/null
```

### Note

- Gli slot `v4l2loopback` creati da `run.sh` sono "kernel-ownati" e restano
  liberi finché non vengono assegnati a un telefono.
- Più telefoni si possono abilitare contemporaneamente senza scaricare/
  ricaricare il modulo, perché la GUI (mai) scarica `v4l2loopback` a runtime.
- Al **riavvio del computer** il modulo viene scaricato insieme al resto del
  kernel e i `/dev/videoN` creati nella sessione precedente spariscono
  (insieme allo state in `/tmp/androidcamera-v4l2/`): nessun demone li
  riattiva da solo, basta rilanciare `./run-linux-gui.sh`.