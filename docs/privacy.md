# Michi privacy policy

Last updated: November 22, 2026

Michi is a free, open-source, unofficial client for [AniList](https://anilist.co), with optional MyAnimeList list support. It has no ads, no analytics and no tracking. Michi is not affiliated with AniList or MyAnimeList.

## What Michi stores on your device

- **Your AniList access token**, after you sign in. It is stored only on your device, is never sent anywhere except to AniList, and is excluded from Android cloud backups and device transfers. Signing out deletes it.
- **Your MyAnimeList access and refresh tokens**, if you choose to sign in with MyAnimeList. They are stored only on your device, are never sent anywhere except to MyAnimeList, and are excluded from Android cloud backups and device transfers. Signing out deletes them.
- **A linked account's access and refresh tokens**, if you link another provider from Settings. They are stored separately from your session tokens, are only sent to that provider, and are excluded from Android cloud backups and device transfers. Unlinking or signing out deletes them.
- **Your app settings** (language, theme, start tab and similar preferences).
- **A cache** of your lists, your profile and images, so the app opens fast and works while the network is slow. You can remove it at any time by clearing the app's storage or uninstalling Michi.

## What Michi sends, and to whom

- Michi talks **only to AniList** (anilist.co and its image servers), **to MyAnimeList** (myanimelist.net for signing in, api.myanimelist.net for your list, and its image server for your avatar) if you sign in with MyAnimeList, **and to AnimeThemes** (api.animethemes.moe), which it asks for an anime's openings and endings using the anime's public AniList id. No account data is sent to AnimeThemes. Everything you do in the app (reading your lists, editing an entry, adding a favourite, changing a synced setting) is a request to the account provider you are signed in with, on your behalf: AniList, or MyAnimeList for the MAL list. The privacy policy of that provider applies to that data: AniList's at https://anilist.co/terms and MyAnimeList's at https://myanimelist.net/privacypolicy.
- AniList remains the source of the catalog (titles, covers and details) even with a MyAnimeList session. To show your MyAnimeList list, Michi asks AniList, without your AniList account, for the works in it by their MyAnimeList ids. AniList therefore receives the ids of the works in your MyAnimeList list, together with your IP address like any web request, but not your MyAnimeList account or token.
- If you link another account, Michi copies changes from your main list to that linked account and can run “Sync now” to catch up. Private AniList entries are not copied. This copy never deletes entries from the linked account, and the linked account's token is sent only to its provider.
- Links you open (streaming sites, trailers, GitHub) open in your browser or in the corresponding app.
- Michi has **no server of its own**. The developer receives no data from the app: no usage statistics, no crash reports, no identifiers.

## Permissions

Michi only uses the internet and network-state permissions, to reach AniList, MyAnimeList (when signed in) and AnimeThemes, and to know when you are offline.

## Children

Michi is not directed at children under 13. AniList accounts are subject to AniList's own age requirements.

## Changes and contact

If this policy changes, the new version will be published at this same address. Questions: open an issue at https://github.com/LuumGrid/michi/issues

---

# Política de privacidad de Michi

Última actualización: 22 de noviembre de 2026

Michi es un cliente no oficial, gratuito y de código abierto para [AniList](https://anilist.co), con soporte opcional de listas de MyAnimeList. No tiene anuncios, analíticas ni rastreo. Michi no está afiliado a AniList ni a MyAnimeList.

## Qué guarda Michi en tu dispositivo

- **Tu token de acceso de AniList**, después de iniciar sesión. Se guarda solo en tu dispositivo, nunca se envía a nadie salvo a AniList y queda fuera de las copias de seguridad en la nube y de las transferencias entre dispositivos de Android. Al cerrar sesión se borra.
- **Tus tokens de acceso y de renovación de MyAnimeList**, si decides iniciar sesión con MyAnimeList. Se guardan solo en tu dispositivo, nunca se envían a nadie salvo a MyAnimeList y quedan fuera de las copias de seguridad en la nube y de las transferencias entre dispositivos de Android. Al cerrar sesión se borran.
- **Los tokens de una cuenta vinculada**, si vinculas otro proveedor desde Ajustes. Se guardan separados de los de la sesión, solo se envían a ese proveedor y quedan fuera de las copias de seguridad en la nube y de las transferencias entre dispositivos de Android. Al desvincular o cerrar sesión se borran.
- **Tus ajustes de la app** (idioma, tema, pestaña de inicio y preferencias parecidas).
- **Una caché** de tus listas, tu perfil e imágenes, para que la app abra rápido y funcione con una red lenta. Puedes borrarla cuando quieras limpiando el almacenamiento de la app o desinstalando Michi.

## Qué envía Michi y a quién

- Michi habla **solo con AniList** (anilist.co y sus servidores de imágenes), **con MyAnimeList** (myanimelist.net para iniciar sesión, api.myanimelist.net para tu lista y su servidor de imágenes para tu avatar) si inicias sesión con MyAnimeList, **y con AnimeThemes** (api.animethemes.moe), a quien pide los openings y endings de un anime con el id público de AniList. A AnimeThemes no se le envía ningún dato de tu cuenta. Todo lo que haces en la app (ver tus listas, editar una entrada, añadir un favorito, cambiar un ajuste sincronizado) es una petición al proveedor de la cuenta con la que estás dentro, en tu nombre: AniList, o MyAnimeList para la lista de MAL. A esos datos se aplica la política de privacidad de ese proveedor: la de AniList en https://anilist.co/terms y la de MyAnimeList en https://myanimelist.net/privacypolicy.
- AniList sigue siendo la fuente del catálogo (títulos, portadas y detalles) incluso con una sesión de MyAnimeList. Para mostrar tu lista de MyAnimeList, Michi pide a AniList, sin tu cuenta de AniList, las obras de esa lista por sus ids de MyAnimeList. Por eso AniList recibe los ids de las obras de tu lista de MyAnimeList, junto con tu dirección IP como en cualquier petición web, pero no tu cuenta ni tu token de MyAnimeList.
- Si vinculas otra cuenta, Michi copia los cambios de tu lista principal a esa cuenta vinculada y puede usar «Sincronizar ahora» para ponerla al día. Las entradas privadas de AniList no se copian. Esta copia nunca borra entradas de la cuenta vinculada, y el token de esa cuenta solo se envía a su proveedor.
- Los enlaces que abres (sitios de streaming, tráileres, GitHub) se abren en tu navegador o en la app correspondiente.
- Michi **no tiene servidor propio**. El desarrollador no recibe ningún dato de la app: ni estadísticas de uso, ni informes de errores, ni identificadores.

## Permisos

Michi solo usa los permisos de internet y de estado de la red, para conectarse a AniList, a MyAnimeList (cuando has iniciado sesión) y a AnimeThemes, y saber cuándo no tienes conexión.

## Menores

Michi no está dirigida a menores de 13 años. Las cuentas de AniList están sujetas a los requisitos de edad de AniList.

## Cambios y contacto

Si esta política cambia, la nueva versión se publicará en esta misma dirección. Dudas: abre un issue en https://github.com/LuumGrid/michi/issues
