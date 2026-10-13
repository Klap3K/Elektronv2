# Praca nad Elektron-Vulcan

Wymagane są JDK 17+ (CI używa 21), Android SDK 35 i build-tools 34.0.0.
Ustaw `JAVA_HOME` oraz `ANDROID_HOME` lub lokalny `sdk.dir` w `local.properties`.

```sh
./gradlew :app:testGithubDebugUnitTest :app:assembleGithubRelease :app:lintGithubRelease
./gradlew -Pelektron.gms=false :app:testGmsDebugUnitTest :app:assembleGmsRelease :app:lintGmsRelease
python3 .github/check_repository_files.py
```

Na Windows użyj `gradlew.bat` i `python`. W PowerShell parametr Gradle zapisuj w cudzysłowie:
`'-Pelektron.gms=false'`. GMS można kompilować i testować z wyłączoną wtyczką Google Services;
działające powiadomienia Firebase wymagają własnego
`app/google-services.json` zgodnie z `PORADNIK_PUSH.md`.

Lokalnie włącz kontrolę plików przed commitem:

```sh
git config core.hooksPath .githooks
```

Ten sam skrypt działa w CI. Klucze, hasła, konfiguracja Firebase, raporty z danymi uczniów
i pliki lokalne nie mogą trafiać do Git. Zasady podpisywania opisuje `SECURITY.md`.

Obsługiwany kanał dystrybucji to GitHub Releases. Wariant `github` zastąpił wcześniejszy
`foss`; opcjonalny `gms` dodaje Firebase. Domyślne wydania używają `github`.

Każda zmiana bazy wymaga migracji i wyeksportowanego schematu `app/schemas/.../<wersja>.json`.
Testy sprawdzają obecność schematów i aktualizację prawdziwych baz v2 oraz v5 bez utraty artykułów.
CI odrzuca build zmieniający lub generujący nieśledzony schemat. Nie zastępuj migracji kasowaniem bazy.

Lint sprawdza kod produkcyjny. Źródła testów Robolectric są pomijane przez lint,
ale są kompilowane i uruchamiane dla obu wariantów w CI; ich analiza FIR/UAST na Windows
może blokować końcowy raport lintu.

Wydanie wymaga taga `v<wersja>` i pliku `release-notes/<wersja>.md`. Publikowane jest wyłącznie
produkcyjne APK; debug służy do lokalnej diagnostyki. Nie uruchamiaj publikacji w celu testowania PR.

Interfejs aplikacji jest obecnie wyłącznie po polsku; angielskie metadane opisują aplikację
w katalogach dystrybucji. Zrzuty w `fastlane/metadata/android/en-US/images/phoneScreenshots`
są źródłem obrazów także dla README.

Aktualizacje zależności zgłasza Dependabot dla Gradle i GitHub Actions. Duże aktualizacje
AGP/Kotlin/Compose/Room/WorkManager wdrażaj osobnymi zmianami, sprawdzając testy,
release z R8 i lint. Przypinaj akcje do pełnego SHA.

Zmiany logowania wymagają również próby na urządzeniu: wybór pojedynczego i wielu profili,
CAPTCHA/SSO, wygaśnięcie sesji, odmowa wejścia na obcą domenę i rozłączenie konta.
Zachowaj testy parserów, brak analityki, sprawdzanie SHA-256 i ograniczenia `SafeUrls`.
