# VVC-MOBILE-AGENT-CORE

Cyberpunk-styled Local Android Agent. Driven by The Van Cartier Authority.

## Rama operativa

`vvc/gradle-wrapper-ci`

## Núcleo offline

La app mantiene inferencia local mediante LiteRT y carga modelos desde:

```text
app/src/main/assets/models/
```

Los binarios `.tflite` no se versionan para evitar errores de PR con archivos binarios; se regeneran localmente. Cada modelo `.tflite` debe tener un archivo lateral `.sha256` generado por:

```bash
python3 tools/download_edge_models.py
```

El script descarga artefactos TFLite de fuentes oficiales de TensorFlow/Google para las habilidades locales:

- `audio_scribe_yamnet_classifier.tflite`: clasificación de audio base para Audio Scribe.
- `mobile_actions_text_classifier.tflite`: clasificación de texto base para Mobile Actions.
- `ask_image_mobilenet_quant_classifier.tflite`: clasificación visual cuantizada para Ask Image.

> Los archivos `.sha256` y `vvc_edge_models_manifest.json` sí se versionan como contrato de integridad; los `.tflite` se descargan con el script anterior dentro de `app/src/main/assets/models/`.

La variante MobileNetV4 de referencia queda disponible como descarga opcional del script con `--include-optional`, pero no se activa por defecto porque la conversión pública localizada es Float16 y no INT8. Para producción estricta INT8, sustituir el archivo Ask Image por el artefacto corporativo aprobado y generar su `.sha256`.



## Gradle Wrapper y CI

El proyecto versiona Gradle Wrapper para evitar depender de instalaciones globales de Gradle. La versión fijada es Gradle 8.13, compatible con Android Gradle Plugin 8.12.1.

Comando oficial de compilación local y CI:

```bash
./gradlew assembleDebug
```

El workflow `.github/workflows/android-build.yml` ejecuta ese mismo comando y publica los APK generados desde `app/build/outputs/apk/debug/*.apk` como artifact.

## Compilación debug en Termux

En Termux/Android, el Android Gradle Plugin puede intentar ejecutar el `aapt2` Linux descargado desde Maven. Ese binario no es ejecutable dentro de Termux/Android y falla durante `:app:processDebugResources` con mensajes como `Syntax error: ")" unexpected` o `AAPT2 ... Daemon startup failed`.

Usa el helper versionado para forzar el `aapt2` instalado dentro del Android SDK local:

```bash
tools/vvc_build_debug_termux.sh
```

El helper realiza estas acciones:

- Detecta el SDK desde `ANDROID_HOME`, `ANDROID_SDK_ROOT` o `~/android-sdk`.
- Regenera `local.properties` con `sdk.dir=...`.
- Verifica que `local.properties` siga protegido por `.gitignore`.
- Busca el `aapt2` de `build-tools` y compila con `-Pandroid.aapt2FromMavenOverride=...`.

Si ya tienes un alias llamado `vvc_build_debug`, apúntalo a este script para evitar volver a ejecutar el `aapt2` Linux de Maven. El helper usa exclusivamente `./gradlew`, no una instalación global de Gradle.

## Seguridad local

- No se usa `js_eval`.
- No se usa `child_process`.
- No se usa JNI libre propio.
- Los modelos se validan con SHA-256 antes de cargarse en memoria.
- Las anomalías de baja confianza o integridad disparan notificaciones locales Android.
