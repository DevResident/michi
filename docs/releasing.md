# Publicar una release

1. Mergea el PR en `main`.
2. Crea el tag en `main` y súbelo:

   ```bash
   git switch main
   git pull
   git tag v1.1.0
   git push origin v1.1.0
   ```

   El tag debe ser exactamente `vX.Y.Z`: el workflow solo se dispara con tags
   que encajan con el patrón glob `v[0-9]+.[0-9]+.[0-9]+`.

3. El workflow **Release** comprueba que el tag coincide con `versionName`
   (`:printVersion`), ejecuta los tests de `shared` en el host de Android, los
   de `buildSrc` y `:androidApp:lintRelease`, y después compila el APK y el AAB
   firmados. Por último crea el GitHub Release del tag y adjunta ambos archivos.
   El cuerpo sale de `docs/release-notes-<major.minor>.md` si existe (para
   `v1.1.0`, `docs/release-notes-1.1.md`).

El workflow falla si el tag no encaja con `vX.Y.Z`, si `versionName` no coincide
con el tag, si el tag no es ancestro de `origin/main` o si falta algún secret.
No publica builds sin firmar.

## Versiones

- `:printVersion` imprime solo `versionName` (`./gradlew -q printVersion`).
  `release.yml` lo compara con el tag antes de compilar.
- `-PrequireTagVersion=true` hace que la build falle si no hay un tag `vX.Y.Z`
  alcanzable, en vez de usar `0.0.0-unknown`. El workflow de release la pasa
  siempre, también cuando hay overrides.
- En un tag `vX.Y.Z`: `versionName = X.Y.Z`.
- Fuera de un tag: `versionName = X.Y.Z+N.<sha>`, donde `N` son los commits
  desde el último tag. Por ejemplo, `1.0.0+22.a403499`.
- `versionCode = X * 10000 + Y * 100 + Z`. `minor` y `patch` deben ser menores
  que 100. `v1.1.0` es `10100`.
- En iOS, `MARKETING_VERSION` es siempre `X.Y.Z` porque Apple no admite el
  sufijo de desarrollo. `CURRENT_PROJECT_VERSION` es el mismo `versionCode`.
  Ajustes → Acerca de muestra la versión completa en Android y iOS.
- `iosApp/Configuration/Version.xcconfig` lo genera
  `:writeIosVersionConfig` durante la build de Xcode y no se versiona.

Para TestFlight, cada subida necesita un tag nuevo (aunque sea un parche):
`CURRENT_PROJECT_VERSION` solo cambia con la versión del tag, no con cada
compilación.

Overrides locales:

```bash
./gradlew :androidApp:assembleDebug \
  -PmichiVersionName=1.2.3-custom \
  -PmichiVersionCode=10203
```

Con `-PrequireTagVersion=true` los overrides no sustituyen al tag: si no hay un
tag `vX.Y.Z` alcanzable, la build falla.

## Integración continua

- **Android checks** (`.github/workflows/android.yml`): en cada PR y en cada
  push a `main` ejecuta `./gradlew -p buildSrc test`,
  `:shared:testAndroidHostTest` y `:androidApp:lintRelease`.
- **iOS framework check** (`.github/workflows/ios.yml`): compila el framework
  compartido en un runner macOS.
- **Release** repite los tests y el lint, con los client ids reales, antes de
  compilar y publicar.

## Probar el workflow sin publicar

La prueba real es el siguiente tag. Para ensayarlo antes sin publicar, usa un
fork: configura allí los secrets (aunque sean de prueba), sube un tag `vX.Y.Z`
y comprueba el trigger, la comprobación de versión, los tests, la firma y la
release del fork. Un tag que no coincida con `versionName` debe fallar en el
paso de versión.

## Secrets del workflow

En **Settings → Secrets and variables → Actions → Repository secrets**:

- `MICHI_KEYSTORE_BASE64` — `base64 < keystore.jks | tr -d '\n'`
- `MICHI_KEYSTORE_PASSWORD`
- `MICHI_KEY_ALIAS`
- `MICHI_KEY_PASSWORD`
- `MAL_CLIENT_ID`
- `ANILIST_CLIENT_ID`

El workflow decodifica el keystore a `$RUNNER_TEMP/release.jks` y pasa esa ruta
a Gradle en `MICHI_KEYSTORE_FILE`. En local se sigue usando `keystore.properties`
y los client ids de `local.properties`.
