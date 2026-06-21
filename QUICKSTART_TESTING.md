# Guía Rápida: Compilar y Probar Lazy Loading

## 🚀 Compilación Local

### Opción 1: En tu PC/Mac

```bash
# 1. Clona o descarga el repo
git clone https://github.com/the-VanCartier-Authority/VVC-MOBILE-AGENT-CORE.git
cd VVC-MOBILE-AGENT-CORE

# 2. Cambia a la rama de optimización
git checkout feature/lazy-model-loading

# 3. Compila con Gradle
./gradlew assembleDebug

# 4. El APK estará en:
# app/build/outputs/apk/debug/app-debug.apk
```

### Opción 2: En Termux (Android)

```bash
# Usa el script helper del README
tools/vvc_build_debug_termux.sh

# O manualmente:
export ANDROID_HOME=$HOME/android-sdk
./gradlew assembleDebug
```

### Opción 3: En GitHub Actions (CI/CD)

El workflow automático en `.github/workflows/android-build.yml` compilará automáticamente cuando hagas push.

---

## 📱 Instalación en Dispositivo

### Desde tu PC/Mac
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Desde Termux
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🧪 Testing

### Test 1: ⚡ Velocidad de Startup (MÁS IMPORTANTE)

**Antes:**
```bash
adb shell am start -W com.vancartier.vvcmobileagentcore/.MainActivity
```

**Busca la línea:**
```
TotalTime: XXXXX
```

**Esperado:**
- ❌ Antes: 5000-10000 ms (5-10 segundos)
- ✅ Después: <1000 ms (<1 segundo)

**Si ves >1000ms:** Algo fue mal, repórtalo

---

### Test 2: 💾 Uso de Memoria

```bash
adb shell dumpsys meminfo com.vancartier.vvcmobileagentcore | head -20
```

**Busca la línea "TOTAL":**

**Esperado:**
- ❌ Antes: 500-600 MB
- ✅ Después: 50-150 MB

**Si ves >300mb:** Algo fue mal, los modelos no se están lazy-loading

---

### Test 3: 🎯 Verificar Lazy Loading en Acción

**Paso 1:** Abre la app
```
Verás: "Inicializando catálogo de modelos..."
```

**Paso 2:** Espera ~500ms
```
Verás: Status "READY" con lista de modelos
```

**Paso 3:** Busca la línea:
```
═══════════════════════════
LAZY LOADING ENABLED
Modelos se cargan bajo demanda
═══════════════════════════
```

✅ Si ves eso, ¡FUNCIONA!

---

### Test 4: 🔄 Inferencia Bajo Demanda

**Primer uso de Audio Scribe:**
- Tarda ~2-3 segundos (cargando modelo en memoria)
- Pero la UI NO SE CONGELA

**Segundo uso de Audio Scribe:**
- Tarda ~100ms (modelo ya está en memoria)
- Mucho más rápido

---

## 📊 Comparativa Visual

### ANTES (Eager Loading)
```
App Start
  ↓ [spinner] Cargando AUDIO_SCRIBE...        2-3s
  ↓ [spinner] Cargando ASK_IMAGE...           2-3s
  ↓ [spinner] Cargando MOBILE_ACTIONS...      1-2s
  ↓ [spinner] Verificando integridad...       1s
  ↓
✅ READY (5-9 segundos después)
```

### DESPUÉS (Lazy Loading)
```
App Start
  ↓ [quick] Catálogo de modelos...            <500ms
  ↓
✅ READY INMEDIATAMENTE
```

---

## ✅ Checklist de Pruebas

- [ ] App compila sin errores: `./gradlew assembleDebug`
- [ ] APK se genera en `app/build/outputs/apk/debug/`
- [ ] APK se instala sin errores: `adb install -r app-debug.apk`
- [ ] App abre sin crashes
- [ ] Startup time < 1 segundo
- [ ] Memoria < 150 MB
- [ ] Ves "LAZY LOADING ENABLED" en la UI
- [ ] Audio Scribe funciona (primer uso ~2-3s, luego rápido)
- [ ] Ask Image funciona
- [ ] Mobile Actions funciona
- [ ] No hay crashes después de usar modelos

---

## 🐛 Troubleshooting

### Error: "Repo is inaccessible or not found"
```
→ Verifica que estés en la rama correcta:
  git branch -a
  git checkout feature/lazy-model-loading
```

### Error: "app-debug.apk not found"
```
→ Compila primero:
  ./gradlew clean assembleDebug
→ Espera a que termine (puede tardar 1-2 minutos)
```

### Error: "adb: not found"
```
→ Android SDK no instalado
→ En Termux: instala android-sdk
→ En PC: descarga desde developer.android.com
```

### App se abre pero muestra error
```
→ Revisa los logs:
  adb logcat | grep -i "vvc\|error"
```

### Primer inference tarda MUCHO (>10s)
```
→ Normal en dispositivos lentos
→ Comprueba que no se congela la UI
→ Si la UI se congela, reporta
```

---

## 📹 Registrar Logs para Reportar

Si algo falla, captura logs:

```bash
# Limpiar logs anteriores
adb logcat -c

# Instalar app
adb install -r app-debug.apk

# Abrir app
adb shell am start com.vancartier.vvcmobileagentcore/.MainActivity

# Capturar logs por 30 segundos
adb logcat > logs.txt &
sleep 30
kill %1

# Revisar
cat logs.txt | grep -i "vvc\|error\|exception"
```

---

## 🎓 Entender los Resultados

### Línea típica de éxito en logcat:
```
I/com.vancartier.vvcmobileagentcore: Model catalog scanned in 250ms
I/com.vancartier.vvcmobileagentcore: Ready with lazy loading enabled
```

### Línea de error típica:
```
E/com.vancartier.vvcmobileagentcore: OOM Exception loading model
E/com.vancartier.vvcmobileagentcore: app crashed with OutOfMemoryError
```

---

## 🔄 Rollback (si es necesario)

Si algo no funciona, revertir a la versión anterior:

```bash
# Opción 1: Volver a main
git checkout main
./gradlew clean assembleDebug

# Opción 2: Específica (si quieres mantener los cambios en git)
git revert feature/lazy-model-loading
./gradlew clean assembleDebug
```

---

## 📞 Reportar Issues

Si encuentras problemas:

1. Captura:
   - `adb logcat` (logs del app)
   - Screenshot del estado actual
   - Output de: `adb shell dumpsys meminfo com.vancartier.vvcmobileagentcore`

2. Describe:
   - Qué dispositivo (modelo, Android version)
   - Qué pasó exactamente
   - Qué esperabas que pasara

3. Abre issue en GitHub con esa info

---

## ✨ Verificación Final

Una vez que todo funcione:

```bash
# Ver commits de la rama
git log main..feature/lazy-model-loading

# Comparar cambios
git diff main feature/lazy-model-loading

# Crear Pull Request cuando esté listo
# (desde GitHub web)
```

---

**¡Listo para probar! 🚀**

Compila, instala y reporta cómo te va.
