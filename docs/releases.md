# Releases and app identity

How Zoot Suit is identified, signed and released, and why.

## App id and versions

- Release builds: `se.jmwhite.zootsuit`, "Zoot Suit". It installs beside Zoo for Zotero,
  not over it.
- Debug builds: `se.jmwhite.zootsuit.debug`, "Zoot Suit (debug)", so a debug and a release
  build can share a device.
- Each release bumps `versionCode` and `versionName` in `app/build.gradle`.
- The Kotlin packages and namespace keep upstream's names (`com.mickstarify.zooforzotero`);
  they are internal and invisible to users.

## Signing

Every release is signed with the same key, so each one installs over the previous one
and keeps the app's data. The certificate's SHA-256:

```
FA:92:B9:89:83:CB:8A:21:78:32:FA:8E:3B:E5:D3:E2:25:75:41:5F:67:29:93:91:91:60:36:A4:04:AC:D5:36
```

`app/build.gradle` reads `keystore.properties` next to `settings.gradle`, or the file
named by `$KEYSTORE_PROPERTIES`, with the keys `storeFile`, `storePassword`,
`keyAlias` and `keyPassword`. Without it the release APK is unsigned. No credentials
are kept in the repository: keystores, `keystore.properties` and `apikeys.properties`
are git-ignored.

## Making a release

1. Bump `versionCode` and `versionName`.
2. `./gradlew assembleRelease`.
3. Check the signature (`apksigner verify --print-certs`) against the certificate above.
4. Create a GitHub release with the tag `vX.Y.Z`, the APK named `zoot-suit-X.Y.Z.apk`
   and a `SHA256SUMS` file.

## Zotero sign-in (OAuth)

- The app is registered as an OAuth client with Zotero; its key and secret go into
  `apikeys.properties` at build time (see `apikeys.properties.example`). Builds
  without them offer sign-in with an API key only.
- Group libraries are requested read-only (`all_groups=read`): the app writes only to
  your personal library.
- The callback is `zootsuit://oauth_callback`, caught by the sign-in WebView; no other
  app can receive it. Zotero's registration form only accepts http(s) callbacks, so
  the registered one is a placeholder and Zotero accepts the app's own per request.
  If Zotero ever requires them to match, the callback will move to an https URL the
  WebView intercepts.

## Network security

`res/xml/network_security_config.xml` trusts the system certificate store plus
ISRG Root X1, Let's Encrypt's root, bundled in `res/raw`. Android 7.0 predates that
root in its own store (added in 7.1.1), so without it HTTPS to Let's Encrypt servers,
including many WebDAV hosts, fails. Certificates installed by the user are not trusted.

Plain `http` is allowed because WebDAV servers are set up at runtime and some use it.
A WebDAV password sent over `http` is not encrypted; use `https` where you can.

## Crash reports

Off. Before they could be turned on: reports would include the device log, and
release logs contain item titles, item keys and WebDAV URLs. That needs filtering
(or dropping the log from reports) and a line in the privacy policy.

## Licence

GPLv3 (`LICENSE`). Upstream ships no licence file; its author states GPLv3 in its
terms of use. The readme marks Zoot Suit as a modified version.
