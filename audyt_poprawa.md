# Audyt i plan poprawy — Elektronv2

**Data audytu:** 13.10.2026
**Audytowana wersja:** commit `b1209d2`, wersja aplikacji `2.0.6` (versionCode 43)
**Cel pliku:** dokument roboczy dla agenta/osoby wdrażającej poprawki po audycie repozytorium.
**Status:** do realizacji — odhaczaj zadania po wdrożeniu i zweryfikowaniu.

> Po zakończeniu wszystkich poprawek usuń ten plik z repozytorium albo przenieś go do zamkniętego zgłoszenia (issue). Nie dopisuj tu żadnych haseł, kluczy, tokenów ani zawartości plików `keystore.properties` / `google-services.json`.

---

## Instrukcje dla agenta wdrażającego

1. Pracuj na bieżącej gałęzi roboczej wskazanej w sesji. Nie twórz i nie wypychaj innych gałęzi.
2. Realizuj zadania w kolejności priorytetów: najpierw A-01 i A-07, potem A-02/A-04/A-05, na końcu porządkowe.
3. Każde zadanie kończ weryfikacją (patrz „Weryfikacja" na dole). Nie oznaczaj zadania jako zrobione bez zielonego CI albo opisanego powodu, dlaczego CI nie wystarcza.
4. **Zadania oznaczone „DECYZJA WŁAŚCICIELA" zatrzymaj i zapytaj właściciela repozytorium przed wdrożeniem** — dotyczą rotacji klucza podpisu, kanałów dystrybucji i polityki zapisywania hasła. Nie podejmuj tych decyzji samodzielnie.
5. Nie commituj: kluczy prywatnych, haseł, `keystore.properties`, `google-services.json`, raportów z danymi użytkowników.
6. Zachowaj istniejące dobre praktyki: brak analityki/reklam, wykluczenia backupu dla bazy i poświadczeń, weryfikację SHA-256 w updaterze, `SafeUrls`.

---

## Podsumowanie oceny

| Obszar | Ocena | Główny problem |
|---|---|---|
| Bezpieczeństwo wydań | 2/10 | A-01: klucz podpisu odtwarzalny z repo |
| Prywatność / poświadczenia | 4/10 | A-02: hasło eduVULCAN jawnie w DataStore |
| Jakość kodu | 7/10 | Duże pliki, przeciążony model konta (A-08, A-11) |
| Testy | 8/10 | 544 testy jednostkowe, brak instrumentalnych/UI |
| CI/CD | 4/10 | Tylko foss/debug, bez lintu i skanów (A-06) |
| Dokumentacja | 6/10 | Miejscami niezgodna z kodem (A-02, A-03) |

Mocne strony do utrzymania: rozbudowane testy parserów i repozytoriów (m.in. prawdziwe zrzuty stron szkoły, Room in-memory, SyncCoordinator, widżety), migracje Room bez kasowania danych, brak trackerów, ręczne wysyłanie raportów awarii, skrypt higieny repozytorium w CI.

---

## 🔴 Krytyczne i wysokie

### [ ] A-01. Prywatny klucz podpisu APK jest faktycznie publiczny — DECYZJA WŁAŚCICIELA przy rotacji

**Dowód:**
- W repo śledzony jest `.github/signing/release.pfx.b64` (certyfikat podpisujący wydania).
- Hasło do archiwum stoi jawnie w `.github/workflows/release.yml` (krok przygotowania klucza, ok. linii 27–42).
- Zweryfikowano podczas audytu: archiwum da się otworzyć tym hasłem i wyodrębnić klucz prywatny. Każdy klonujący repo może podpisać złośliwe APK jako legalną aktualizację (aplikacja ma wbudowany instalator aktualizacji; Android ufa temu samemu kluczowi).

**Do zrobienia:**
- [ ] Przenieś materiał klucza do GitHub Secrets (osobno zawartość klucza i hasła); workflow ma przestać zawierać jakiekolwiek hasło w treści.
- [ ] Usuń `.github/signing/release.pfx.b64` z repo i przepisz historię (`git filter-repo`), ze świadomością, że kopie klucza mogły już powstać.
- [ ] DECYZJA WŁAŚCICIELA: rotacja klucza podpisu. Rotacja łamie aktualizacje w miejscu u obecnych użytkowników — wymaga planu komunikacji i ewentualnej wersji przejściowej (rozważ linię rotacji APK Signature Scheme v3). Bez rotacji traktuj obecny klucz jako spalony i zaplanuj migrację.
- [ ] Do czasu decyzji o rotacji: ogranicz, kto może tworzyć tagi `v*` i wypuszczać release'y.
- [ ] Po wdrożeniu: zweryfikuj, że `git grep` po repo (łącznie z workflowami) nie znajduje haseł ani materiału klucza, a release z taga nadal się buduje i podpisuje.

**Kryterium odbioru:** świeży klon repo nie zawiera niczego, co pozwala podpisać APK; workflow release działa na sekretach; decyzja o rotacji jest zapisana w issue/PR.

### [ ] A-02. Hasło eduVULCAN zapisywane jawnie w DataStore — DECYZJA WŁAŚCICIELA przy polityce hasła

**Dowód:**
- `app/src/main/kotlin/pl/zse/bydgoszcz/elektron/data/repository/CalendarRepositoryImpl.kt` (klucze ok. linii 67–77, zapis ok. 147–163): hasło logowania (`VULCAN_SAVED_PASSWORD`), token i ciasteczka sesji trafiają do zwykłego, nieszyfrowanego `preferencesDataStore`. Brak AndroidX Security/Keystore w zależnościach (`gradle/libs.versions.toml`, `app/build.gradle.kts`).
- `PRYWATNOSC.md` twierdzi, że aplikacja nie prosi o hasło główne — a istnieje pełny przepływ `loginEduVulcanWithPassword` z cichym odnawianiem sesji (`EduVulcanWebAuthenticator.refreshSessionSilently`). Użytkownicy to m.in. niepełnoletni uczniowie.
- Łagodząco (utrzymać!): `calendar_preferences` jest wykluczony z cloud-backup i device-transfer (`app/src/main/res/xml/data_extraction_rules.xml`, `backup_rules.xml`).

**Do zrobienia:**
- [ ] Przenieś hasło, token i ciasteczka sesji do magazynu szyfrowanego kluczem z Android Keystore (np. EncryptedFile/DataStore z Tink albo własna warstwa AES-GCM na Keystore).
- [ ] Dodaj migrację istniejących wartości jawnych do magazynu szyfrowanego i kasowanie starych wpisów po migracji.
- [ ] DECYZJA WŁAŚCICIELA: czy hasło ma być w ogóle zapamiętywane (ciche odnawianie sesji), czy tylko sesja/ciasteczka z ponownym logowaniem po wygaśnięciu.
- [ ] Zaktualizuj `PRYWATNOSC.md`: opisz zapisywanie hasła/sesji, cel i sposób usunięcia (rozłączenie konta już kasuje klucze — utrzymaj to).
- [ ] Test: po migracji plik DataStore nie zawiera hasła w postaci czytelnej (test jednostkowy z magazynem testowym / weryfikacja migracji).

**Kryterium odbioru:** brak poświadczeń w jawnym DataStore, polityka prywatności zgodna z kodem, rozłączenie konta kasuje wszystkie sekrety.

### [ ] A-03. Wariant `foss` nie odpowiada opisom — DECYZJA WŁAŚCICIELA przy kanałach dystrybucji

**Dowód:**
- `app/build.gradle.kts` (ok. linii 50–58): warianty `gms` i `foss` mają `UPDATE_CHECK=true`; `foss` zawiera pełny `ApkUpdateInstaller`, uprawnienie `REQUEST_INSTALL_PACKAGES` i FileProvidera (`app/src/foss/...`).
- Komentarz w `app/src/gms/AndroidManifest.xml` i `PRYWATNOSC.md` twierdzą, że wersja F-Droid nie ma updatera i nie kontaktuje się z GitHubem — to nieprawda względem kodu. Taki wariant prawdopodobnie nie przeszedłby przeglądu F-Droid.
- `.github/workflows/release.yml` buduje na GitHub Release właśnie wariant `foss` — nazwa straciła znaczenie.
- `ApkUpdateInstaller` istnieje w dwóch niemal identycznych kopiach (foss/gms) i już się lekko rozjeżdża (różnice w komentarzach/nazwach plików).

**Do zrobienia:**
- [ ] DECYZJA WŁAŚCICIELA: docelowe kanały — (a) tylko GitHub Releases, (b) GitHub + F-Droid, (c) GitHub + Google Play.
- [ ] Przy wariancie (b/c): rozdziel smaki uczciwie, np. `github` (updater włączony) i `fdroid` (`UPDATE_CHECK=false`, stub `UpdateInstaller`, brak `REQUEST_INSTALL_PACKAGES`). Przy wariancie (a): przemianuj `foss` na nazwę zgodną z przeznaczeniem.
- [ ] Przenieś wspólną logikę `ApkUpdateInstaller` do `main`, różnicuj smakami tylko moduł DI (`UpdateModule`) i manifest.
- [ ] Uzgodnij komentarze w manifestach, `PRYWATNOSC.md` i README ze stanem faktycznym.

**Kryterium odbioru:** nazwa wariantu == zachowanie; build F-Droid (jeśli utrzymywany) nie zawiera samoinstalacji APK; jedna implementacja instalatora.

### [ ] A-04. Brak wyeksportowanego schematu Room dla wersji 6

**Dowód:**
- `app/src/main/kotlin/pl/zse/bydgoszcz/elektron/data/local/DatabaseMigrations.kt`: `DATABASE_VERSION = 6`, migracja 5→6 istnieje, ale `app/schemas/pl.zse.bydgoszcz.elektron.data.local.AppDatabase/` zawiera tylko `2.json`–`5.json`. Komentarz w `AppDatabase.kt` nakazuje commitować schematy.
- `DatabaseMigrationsTest` sprawdza tylko łańcuch migracji, nie obecność/zgodność pliku schematu — CI tego nie wyłapie; lokalny build generuje `6.json` i brudzi drzewo.

**Do zrobienia:**
- [ ] Wygeneruj i commituj schemat `6.json`.
- [ ] Dodaj test migracji na prawdziwej bazie (np. `MigrationTestHelper`: utwórz bazę v2/v5, migruj do 6, zweryfikuj schemat i zachowanie przykładowych danych).
- [ ] Dopisz do instrukcji w `DatabaseMigrations.kt`, że brak pliku schematu ma wywalać test.

**Kryterium odbioru:** `git status` czysty po buildzie; test migracji przechodzi; schemat 6 jest w repo.

---

## 🟠 Średnie

### [ ] A-05. WebView logowania: mostek JS bez allowlisty hostów

**Dowód:** `EduVulcanWebAuthenticator.kt` (ok. 617–691) i `EduVulcanLoginDialog.kt` (ok. 371+): włączony JavaScript i ciasteczka firm trzecich, `addJavascriptInterface("AndroidEduVulcan")`, `shouldOverrideUrlLoading` zawsze pozwala na nawigację, skrypt z loginem i hasłem wstrzykiwany przy każdym `onPageFinished` bez względu na domenę; brak obsługi błędów SSL.

**Do zrobienia:**
- [ ] Ogranicz wstrzykiwanie skryptu i działanie mostka JS do dozwolonych hostów (domeny eduVULCAN/VULCAN); poza allowlistą nie wstrzykuj poświadczeń.
- [ ] Zablokuj nawigację WebView poza allowlistę (albo otwieraj ją w przeglądarce systemowej).
- [ ] Jawnie obsługuj `onReceivedSslError` przez anulowanie ładowania.
- [ ] Rozważ wyłączenie ciasteczek firm trzecich, jeśli logowanie SSO na to pozwala (zweryfikuj ręcznie scenariusz logowania).

**Kryterium odbioru:** przejście WebView na obcą domenę nie wykonuje skryptu logowania; logowanie eduVULCAN nadal działa end-to-end (test ręczny opisany w PR).

### [ ] A-06. CI nie buduje tego, co trafia do użytkowników; zależności bez nadzoru

**Dowód:** `.github/workflows/ci.yml` uruchamia tylko `testFossDebugUnitTest` + `assembleFossDebug`. Nie buduje: wariantu `gms`, release z R8/minify, testów z `app/src/testGms`. Brak Android Lint, detekt/ktlint, skanowania zależności i Dependabota/Renovate. Akcje na mutowalnych tagach (`@v4`, `softprops/action-gh-release@v2`). Zależności z września 2024: AGP 8.6.1, Kotlin 2.0.20, Compose BOM 2024.09.02, Room 2.6.1, WorkManager 2.9.1 (`gradle/libs.versions.toml`); `org.osmdroid:osmdroid-android:6.1.20` wpisany poza katalogiem wersji; brak `gradle/verification-metadata.xml`.

**Do zrobienia:**
- [ ] Dodaj do CI build release (`assembleFossRelease` lub odpowiednik po A-03) i uruchomianie testów wariantu GMS (z `google-services.json` wstrzykniętym z sekretu lub plikiem testowym).
- [ ] Dodaj `lint` (przynajmniej dla wariantu release) i egzekwuj brak nowych błędów.
- [ ] Dodaj Dependabota/Renovate dla Gradle i GitHub Actions; przypnij akcje do pełnych SHA.
- [ ] Zaplanuj aktualizację zależności (AGP/Kotlin/Compose/Room/WorkManager) osobnymi PR-ami z zielonymi testami.
- [ ] Przenieś osmdroid do `gradle/libs.versions.toml`; rozważ `verification-metadata.xml`.

**Kryterium odbioru:** PR nie przejdzie bez buildu release i testów obu wariantów; aktualizacje zależności pojawiają się automatycznie jako PR-y.

### [ ] A-07. Release publikuje APK debug i ma sztywny opis wydania

**Dowód:** `.github/workflows/release.yml` (ok. 48–72) buduje `assembleFossRelease` i `assembleFossDebug`, publikuje oba APK, a opis wydania jest stałym tekstem z listą nowości konkretnej wersji — przy kolejnym tagu ogłosi to samo.

**Do zrobienia:**
- [ ] Przestań publikować APK debug w GitHub Releases (debug zostaje co najwyżej artefaktem CI z krótką retencją).
- [ ] Generuj opis wydania z `release-notes/<tag>.md` (pliki już istnieją dla wydań finalnych); przerwij release, gdy pliku brak.

**Kryterium odbioru:** release zawiera tylko podpisane APK produkcyjne; opis odpowiada tagowi.

### [ ] A-08. Model konta VULCAN przeciąża pola o cudzych znaczeniach

**Dowód:** `VulcanAccount` i mapowanie w `CalendarRepositoryImpl.kt` (ok. 82–110): dla sesji eduVULCAN pole `certificatePem` przechowuje domenę, `privateKeyPkcs8Base64` — nagłówek ciasteczek, `certificateThumbprint` — login. Ryzyko pomyłek i przypadkowego ujawnienia (log/raport), utrudnia A-02.

**Do zrobienia:**
- [ ] Wprowadź osobne typy: konto Hebe (token/certyfikat/klucz) i sesja eduVULCAN (domena, symbol, ciasteczka, login) albo jedną klasę z jawnymi, opcjonalnymi polami o prawdziwych nazwach.
- [ ] Dostosuj persystencję (razem z A-02) i testy `VulcanApiClientTest`/`CalendarRepositoryImpl`.
- [ ] Upewnij się, że żaden log ani raport awarii nie wypisuje pól z sekretami.

**Kryterium odbioru:** nazwy pól odpowiadają zawartości; testy przechodzą; brak sekretów w logach/raportach.

---

## 🟡 Niskie / porządkowe

### [ ] A-09. Historia gita: jeden squashowany commit

Całe repo to 1 commit — brak `blame`/proweniencji (istotne też przy GPL i współpracy). Na przyszłość: normalne PR-y z czytelną historią. (Ochrony gałęzi `main` nie dało się zweryfikować z audytu — sprawdź w ustawieniach repo: wymagane review i zielone CI przed merge.)

### [ ] A-10. i18n i dostępność

8 stringów w `app/src/main/res/values/strings.xml`, ~237 zakodowanych polskich tekstów w Compose, zero `stringResource` — przy metadanych `en-US` w fastlane. Zdecyduj: albo aplikacja oficjalnie tylko PL (zapisz to w README/fastlane), albo stopniowa ekstrakcja stringów. Brak testów UI/dostępności — dodaj przynajmniej podstawowe testy Compose dla krytycznych ekranów (logowanie, plan, zastępstwa).

### [ ] A-11. Duże pliki i wymuszenia `!!`

- Największe: `VulcanApiClient.kt` (1189 linii), `DashboardScreen.kt` (1141), `TimetableScreen.kt` (936), `EduVulcanWebAuthenticator.kt` (715). Podziel przy okazji innych prac w tych plikach (bez wielkiego refaktoru naraz).
- `VulcanApiClient`: 7× `toHttpUrlOrNull()!!` na URL-ach budowanych z `domain`/`symbol` — zamień na `Result.failure` z czytelnym błędem (nietypowe dane konta nie mogą crashować synchronizacji).

### [ ] A-12. Porządki repozytorium

- [ ] Zrzuty ekranu zduplikowane między `docs/screenshots/` a `fastlane/metadata/android/en-US/images/phoneScreenshots/` (~3,9 MB z 12 MB repo) — zostaw jedno źródło i generuj/kopiuj w CI albo przez fastlane.
- [ ] `fastlane/metadata/android/en-US/changelogs/` kończy się na versionCode 36 przy bieżącym 43 — uzupełnij albo generuj z `release-notes`.
- [ ] `.githooks/pre-commit` nie aktywuje się sam (`core.hooksPath` nieudokumentowane) — dodaj instrukcję do README/CONTRIBUTING albo egzekwuj tylko w CI i usuń martwy hak.
- [ ] Rozważ usunięcie wyjątku cleartext dla `zse.edu.bydgoszcz.pl` w `network_security_config.xml`, skoro mappery wymuszają HTTPS.
- [ ] Usługa FCM używa `runBlocking` (ograniczone timeoutem 8 s) — przenieś obsługę na `goAsync`/WorkManager przy najbliższej pracy nad pushem.
- [ ] Dodaj `SECURITY.md` (kanał zgłaszania podatności) i `CONTRIBUTING.md` (build, testy, hooki, zasady sekretów).

---

## Kolejność wdrożenia

1. **Natychmiast (24–48 h):** A-01 (sekrety; decyzja o rotacji), A-07.
2. **1–2 tygodnie:** A-02, A-04, A-05.
3. **Do miesiąca:** A-03, A-06, A-08, potem A-09…A-12.

## Weryfikacja po każdej poprawce

```sh
python3 .github/check_repository_files.py
./gradlew :app:testFossDebugUnitTest :app:assembleFossDebug
./gradlew :app:assembleFossRelease   # albo odpowiednik po zmianie smaków w A-03
```

Dodatkowo dla A-01/A-07: testowy tag lub `workflow_dispatch` i ręczna kontrola artefaktów release'u. Dla A-02/A-04/A-05: testy jednostkowe migracji/szyfrowania opisane przy zadaniach oraz ręczny scenariusz logowania eduVULCAN.

## Czego audyt nie zweryfikował

- Zachowania aplikacji na urządzeniu (wydajność, bateria, WorkManager/FCM w terenie) — wymaga testów instrumentalnych i profilowania.
- Ustawień repozytorium na GitHubie (branch protection, uprawnienia do tagów) — brak dostępu przez API w czasie audytu.
- Poprawności migracji Room na prawdziwej bazie starszych wersji — do uzupełnienia w A-04.
