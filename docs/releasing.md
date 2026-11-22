# Publicar una release

1. Mergea el PR en `main`.
2. Crea el tag en `main` y súbelo:

   ```bash
   git switch main
   git pull
   git tag v1.1.0
   git push origin v1.1.0
   ```

3. El workflow **Release** compila el APK y el AAB firmados, crea el GitHub
   Release del tag y adjunta ambos archivos. El cuerpo sale de
   `docs/release-notes-<major.minor>.md` si existe (para `v1.1.0`,
   `docs/release-notes-1.1.md`).

El workflow falla si el tag no es ancestro de `origin/main` o si falta algún
secret. No publica builds sin firmar.

## Versiones

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
