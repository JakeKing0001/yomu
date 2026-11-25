# Yomu per Android

Yomu per Android è un fork di [Mihon](https://github.com/mihonapp/mihon) (commit `f8e30a8`),
distribuito con la stessa licenza Apache 2.0 (vedi `LICENSE`). Rispetto a Mihon cambiano nome, icona,
`applicationId`, tema, interfaccia in vetro e impostazioni predefinite; il controllo aggiornamenti
e la telemetria sono disattivati.

Componenti di terze parti aggiunti:

- [AndroidLiquidGlass / backdrop](https://github.com/Kyant0/AndroidLiquidGlass) di Kyant, Apache 2.0
- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) General x4v3 di Xintao Wang, BSD 3-Clause, modello
  esportato da [Qualcomm AI Hub](https://huggingface.co/qualcomm/Real-ESRGAN-General-x4v3)
  (`app/src/main/assets/yomu/real_esrgan.LICENSE`)
- [Inter](https://github.com/rsms/inter), SIL Open Font License 1.1 (`app/src/main/assets/licenses/Inter-OFL.txt`)
