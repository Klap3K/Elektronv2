# Elektronv2 — audyt techniczny i plan napraw dla kolejnego modelu

Data: 2026-10-13. Repozytorium: https://github.com/Klap3K/Elektronv2.
Punkt odniesienia przed publikacją tego dokumentu: `7c66ee5b17028fdd239fd41f17327cdcae008830` na `main`.
Poprzedni audyt: https://github.com/Klap3K/Elektronv2/pull/1, plik `audyt_poprawa.md` w gałęzi PR.

## 1. Najpierw przeczytaj: zakres, ograniczenia i decyzje właściciela

To przekrojowy audyt kodu, konfiguracji Androida, danych, logowania, aktualizatora, CI/CD, testów i dokumentacji. Nie jest certyfikacją bezpieczeństwa ani testem penetracyjnym działającego eduVULCAN. Nie wykonano logowania prawdziwymi poświadczeniami, prób na kontach uczniów, pomiarów baterii, testów na fizycznym telefonie ani przeglądu prywatnych ustawień GitHub. Nie należy twierdzić, że wszystkie możliwe błędy zostały wykluczone.

W czasie audytu istniał duży lokalny pakiet poprawek, niewysłany jeszcze na GitHub. Poniżej oznaczam:

- **BAZA**: problem potwierdzony w wskazanym commicie na GitHubie.
- **LOKALNIE**: poprawka przygotowana i sprawdzona w tym workspace; nie oznacza, że znajduje się w klonie kolejnego modelu.
- **OTWARTE**: problem pozostaje także po przygotowanych poprawkach.
- **WERYFIKACJA**: ryzyko lub brak testu, a nie potwierdzona exploitable luka.

Przed implementacją porównaj aktualny HEAD ze wskazanym punktem odniesienia. Właściciel poprosił następnie o publikację poprawionej aplikacji: pakiet lokalny może zostać wysłany już po publikacji audytu. Wtedy nie wdrażaj go drugi raz. Ten dokument opisuje moment przeglądu, nie dynamiczny status wszystkich późniejszych commitów.

Wiążące decyzje właściciela:

1. Dystrybucja tylko przez **GitHub Releases**, bez F-Droid i Google Play. Wariant bez Firebase jest domyślny; GMS może pozostać opcjonalny.
2. Zachować zapamiętywanie hasła eduVULCAN do odnawiania sesji, ale przechowywać je zaszyfrowane.
3. Przygotować instrukcję rotacji ujawnionego klucza i czyszczenia historii. **Nie rotować klucza i nie przepisywać historii bez nowej zgody właściciela.**
4. Audyt ma być plikiem w repozytorium. **Nie publikować audytu jako asset w Releases.** Releases mają zawierać aplikację.
5. Nie publikować APK podpisanego lokalnym kluczem testowym. Nie kopiować do dokumentacji ujawnionych haseł, kluczy, cookies ani danych uczniów.

## 2. Architektura i granice zaufania

Projekt to natywna aplikacja Android: Kotlin, Compose, Hilt, Room, DataStore, WorkManager, OkHttp, Jsoup, Glance i opcjonalny Firebase Messaging. To klient usług zewnętrznych, a nie własny backend z użytkownikami i JWT. Nie dodawaj do niego fikcyjnego serwera autoryzacyjnego ani mechanicznych zaleceń CORS/RBAC.

Najważniejsze miejsca:

| Obszar | Pliki/katalogi |
| --- | --- |
| Build i zależności | `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/` |
| Manifest i platforma | `app/src/main/AndroidManifest.xml`, manifesty flavorów, `app/src/main/res/xml/` |
| Konto i synchronizacja | `data/repository/CalendarRepositoryImpl.kt`, `data/remote/vulcan/`, `domain/model/VulcanAccount.kt` |
| Dane użytkownika | `data/local/Entities.kt`, `Daos.kt`, `AppDatabase.kt`, `DatabaseMigrations.kt`, repozytoria ocen/frekwencji/notatek |
| Import z powiadomień | `service/EduVulcanNotificationListener.kt` |
| HTTP i parsowanie | `data/remote/http/`, `data/remote/parser/`, `data/remote/sources/` |
| Aktualizacje | `data/update/`, `domain/repository/UpdateRepository.kt` |
| Tło i urządzenie | `work/`, `service/AutoDnd*`, `widget/`, `domain/sync/` |
| Diagnostyka | `crash/`, `presentation/common/SafeUrls.kt` |
| Automatyzacja | `.github/workflows/`, `.github/check_repository_files.py`, `.githooks/` |

Ścieżki pakietowe powyżej są względne do `app/src/main/kotlin/pl/zse/bydgoszcz/elektron/`, o ile nie wskazano inaczej. Dane z RSS/HTML/iCal/FCM/JS traktuj jako niezaufane. Chronione zasoby to hasło, sesja, konto/profil ucznia, oceny, frekwencja, prywatny terminarz i klucz podpisu.

## 3. Wyniki wykonanych sprawdzeń

Dotyczą **lokalnego pakietu poprawek**, nie czystego commita bazowego:

- `testGithubDebugUnitTest`: 565 testów, 0 failures, 0 errors.
- `testGmsDebugUnitTest`: 566 testów, 0 failures, 0 errors.
- Łącznie 1131 wykonań testów; duża część jest wspólna dla obu wariantów, więc to nie 1131 unikalnych scenariuszy.
- `assembleGithubRelease` i `assembleGmsRelease`: zbudowane z R8 i podpisem **testowym**. `apksigner verify` potwierdził poprawność podpisu, nie tożsamość produkcyjnego wydawcy.
- `lintGithubRelease`: 0 errors, 77 warnings; `lintGmsRelease`: 0 errors, 78 warnings. Dodatkowo po 2 wpisy informacyjne w XML. Ostrzeżenia nie zostały wszystkie usunięte.
- Lint produkcyjnych źródeł z `ignoreTestSources = true`. Pełna analiza testowych źródeł utknęła w Kotlin FIR/UAST i została przerwana; testy były osobno kompilowane i wykonywane. Nie przedstawiaj tej konfiguracji jako pełnego lintu testów.
- `actionlint` i `git diff --check`: poprawne.
- Kontrola higieny: poprawna dla tymczasowego indeksu zawierającego kandydat poprawek. W niezmienionym indeksie bazowym nadal występował usuwany klucz.
- Schemat Room 6 wygenerowany przez rzeczywisty KSP, a nie napisany ręcznie.
- Testy migracji z rzeczywistą bazą SQLite obejmują m.in. ścieżki 2→6 i 5→6.

Brak: testów live SSO/CAPTCHA, sprzętowego AndroidKeyStore, odtworzenia backupu na dwóch telefonach, działania powiadomień w Doze, produkcyjnego Firebase, pełnego audytu podatności wszystkich zależności oraz wizualnego przeglądu wszystkich ekranów. Przechodzące testy nie zamykają ustaleń poniżej.

## 4. Ustalenia i instrukcje napraw

Priorytety: P0 — blokada bezpiecznego wydania; P1 — dane/bezpieczeństwo i istotna funkcjonalność; P2 — niezawodność i utrzymanie; P3 — porządki. Dla każdego zadania: najpierw test reprodukujący, potem mały patch, test regresji i aktualizacja statusu audytu.

### A01 — P0: ujawniony klucz podpisu pozostaje kompromitowany

**BAZA; LOKALNIE usunięty z bieżących plików, ryzyko historyczne OTWARTE.** Dowód: `.github/signing/release.pfx.b64` jest śledzony w bazowym HEAD; wcześniejszy workflow zawierał dane podpisu. Base64 nie jest szyfrowaniem. Usunięcie z nowego commita nie usuwa kopii w historii, klonach i forkach.

1. Usuń plik z aktualnego drzewa i dodaj zakazy kluczy do `.gitignore` oraz kontrolera higieny.
2. Przenieś podpis do czterech Secrets: `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`; plik tylko w `runner.temp`, restrykcyjne uprawnienia, usunięcie `always()`.
3. Ustaw ochronę środowiska release i ograniczenia tworzenia tagów po stronie GitHub. Wymaga właściciela/uprawnień administracyjnych; nie uznawaj tego za skonfigurowane na podstawie YAML.
4. Udokumentuj incydent, kompromis kompatybilności aktualizacji i osobny plan rotacji/history rewrite. Lokalny `SECURITY.md` ma instrukcję. Nie wykonuj rotacji ani force-push bez zgody.
5. Przed wydaniem porównaj fingerprint certyfikatu z wcześniej zaufanym APK. Sama zgodność fingerprintu z ujawnionym kluczem nie przywraca jego bezpieczeństwa.

Akceptacja: brak kluczy/haseł w nowych plikach, poprawna kontrola higieny, podpis produkcyjny sprawdzony, jawnie zapisane ryzyko kompromitacji i decyzja właściciela. Nie oznaczać incydentu jako definitywnie zamkniętego.

### A02 — P1: plaintext hasła, sesji i prywatnego URL kalendarza

**BAZA; LOKALNIE przygotowano szyfrowanie.** Pliki: `CalendarRepositoryImpl.kt`, nowy `data/local/CredentialEncryption.kt`. Stare preference przechowywały hasło i sesję bez szyfrowania.

1. Zastosuj AES-GCM z kluczem AndroidKeyStore, nowym losowym IV dla każdej wartości i nazwą pola jako AAD.
2. Zaszyfruj hasło, cookies, klucze/tokeny, prywatny iCal URL i dane identyfikacyjne. Nie zastępuj tego hashowaniem: aplikacja musi odzyskać hasło dla zewnętrznego logowania.
3. Przenieś stare pola w atomowej DataStore migration. Nie usuwaj plaintextu przed udanym wytworzeniem ciphertextu i zatwierdzeniem migracji.
4. Oddziel pola eduVULCAN (`studentKey`, `domain`, `loginUsername`, `cookiesHeader`) od pól certyfikatów Hebe.
5. Dodaj testy losowości IV, AAD, uszkodzenia danych i zachowania oryginału przy błędzie migracji. Istnieją lokalne `CredentialEncryptionTest`.
6. Wykonaj test sprzętowego Keystore i migracji starej instalacji; JVM test używa zwykłego testowego klucza AES, nie potwierdza działania sprzętowego Keystore.

Akceptacja: w serializowanym DataStore nie ma syntetycznych sekretów plaintext, odnowienie sesji działa po restarcie, brak sekretów w `toString` i logach.

### A03 — P1: brak niezawodnej ścieżki odzyskania po uszkodzeniu magazynu poświadczeń

**OTWARTE/WERYFIKACJA lokalnej poprawki.** `CalendarRepositoryImpl.vulcanAccount.catch` wywołuje `saveSyncError`, korzystające z tego samego DataStore. Jeśli zawiedzie inicjalizacja/migracja samego DataStore, zapis błędu może również zawieść; komunikat „odłącz i zaloguj ponownie” nie gwarantuje działającego resetu.

1. Wprowadź typowany stan: brak konta, aktywne konto, wymagane logowanie, uszkodzony magazyn.
2. Nie zapisuj komunikatu o niedostępności magazynu do tego samego niedostępnego magazynu.
3. Dodaj świadomy reset wyłącznie magazynu poświadczeń; nie kasuj ręcznych ocen/notatek. Unikaj tworzenia dwóch DataStore dla tego samego pliku.
4. Testuj błąd dostawcy klucza, uszkodzony protobuf, zły tag GCM i błąd migracji. Reprodukcję błędu migracji traktuj osobno od błędu decrypt.

Akceptacja: użytkownik może odzyskać logowanie bez reinstalacji i bez utraty ręcznych danych; brak pętli wyjątków w flow.

### A04 — P1: WebView bridge i granice domen

**BAZA; LOKALNIE ograniczony most.** Pliki: `EduVulcanLoginDialog.kt`, `EduVulcanWebAuthenticator.kt`, nowy `EduVulcanWebSecurity.kt`.

1. Usuń refleksyjny `addJavascriptInterface`; użyj origin-scoped `WebViewCompat.addWebMessageListener` z kontrolą main-frame i metod allowlist.
2. Akceptuj HTTPS/443, bez userinfo, tylko rzeczywiste domeny Vulcan i poprawne subdomeny. Waliduj adres również tuż przed wstrzyknięciem JS i wewnątrz skryptu, aby ograniczyć wyścig nawigacji.
3. Wyłącz file/content access i mixed content; błędny TLS kończ przez `handler.cancel()`. Brak wsparcia bezpiecznego bridge ma kończyć logowanie czytelnym błędem, nie niebezpiecznym fallbackiem.
4. Testuj obce iframe, domeny podobne, alternatywne porty, userinfo, nawigację w trakcie injection i wiadomości o nieznanej metodzie.
5. Sprawdź na prawdziwym urządzeniu SSO, wygaśnięcie sesji, CAPTCHA i wybór wielu profili. Nie poszerzaj wildcardów tylko po to, by usunąć błąd logowania.
6. Doprecyzuj ograniczenia długości payloadów/JSON i statusów pochodzących z JS.

Akceptacja: obca strona nie wywołuje operacji natywnych; poprawne logowanie nadal działa. Ostrzeżenie lint `RequiresFeature` przy lokalnej poprawce wymaga czytelnego feature-guard lub wąskiego, uzasadnionego suppression, nie globalnego wyłączenia.

### A05 — P1: wyścigi i mieszanie danych przy zmianie/odłączeniu konta

**OTWARTE mimo częściowego lokalnego Mutex.** `CalendarRepositoryImpl` serializuje własne operacje, ale `AttendanceRepositoryImpl.sync`, `GradeRepositoryImpl.syncFromEduVulcan` i `EduVulcanNotificationListener` zapisują do DAO poza tym Mutex. Nowe podłączenie konta zapisuje sesję przed synchronizacją, bez atomowego usunięcia danych poprzedniego ucznia.

1. Testuj: sync A startuje → odłączenie → spóźniony wynik A; konto A → konto B, którego fetch się nie udaje; powiadomienie podczas odłączania.
2. Wprowadź account/profile identity i generation do importów. Przed zatwierdzeniem odpowiedzi sprawdź, że generation nadal aktualne.
3. Scoping danych źródłowych po koncie/profilu lub centralny mechanizm transakcji i blokad powinien obejmować wszystkie ścieżki zapisów, nie tylko jedną klasę.
4. Przy przełączeniu usuń/ukryj dane starego konta, zachowując jawnie ręczne dane zgodnie z polityką produktu. CookieJar/WebView nie może mieszać sesji różnych kont.
5. Zaplanuj migrację Room przy dodaniu identity/source; nie zmieniaj po cichu schematu 6.

Akceptacja: stare dane nie pojawiają się przy koncie B ani po wylogowaniu; opóźniony zapis nie odtwarza usuniętych importów.

### A06 — P1: frekwencja demonstracyjna wygląda jak zsynchronizowane dane

**BAZA i OTWARTE.** `AttendanceRepositoryImpl.sync`: przy pustej bazie zapisuje zestaw `initial` z przykładowymi statystykami. Dotyczy również ścieżki po nieudanym fetch podłączonego konta, a wynik zwraca sukces. Użytkownik może zobaczyć zmyślone liczby zamiast błędu sieci.

1. Dodaj test: pusty DAO + konto + HTTP 500/401 → żadnych wstawionych danych demo.
2. Usuń automatyczne seedowanie danych przykładowych z produkcyjnej synchronizacji.
3. Jeśli symulator potrzebuje przykładu, przechowuj go wyłącznie w jawnym trybie demo ze stałym oznaczeniem, poza tabelą rzeczywistych importów.
4. Rozróżnij Empty/Error/Stale/Success i pokazuj datę ostatniego prawidłowego odczytu.

Akceptacja: błąd API nigdy nie tworzy fikcyjnej frekwencji; demo wymaga świadomego wyboru.

### A07 — P1: częściowe odpowiedzi API mogą usuwać poprawne dane

**BAZA i OTWARTE.** `VulcanApiClient.fetchEduVulcanGrades` pomija nieudane okresy przez `continue`, a następnie zwraca `Result.success(allGrades)`. Analogicznie pobieranie frekwencji pomija przedmioty. Repozytoria używają `replaceAll`, więc częściowy wynik może usunąć wcześniej dostępne rekordy. Z kolei `CalendarRepositoryImpl` pomija aktualizację ocen/frekwencji przy prawidłowej pustej liście, pozostawiając stare dane. Sukces jednego źródła terminarza wystarcza do `schoolEventDao.replaceAll`, nawet gdy inne źródło zawiedzie.

1. Przygotuj fixture: dwa okresy/przedmioty, drugi HTTP 500; oraz prawidłowa pusta odpowiedź; oraz iCal success + Vulcan failure.
2. Zwracaj pełność wyniku per źródło/okres, nie samą listę bez informacji o błędach.
3. Zastępuj wyłącznie kompletny, potwierdzony snapshot danego zakresu. Dla częściowego wyniku zachowaj dotychczasowe dane i oznacz stale/error.
4. Prawidłowy pusty snapshot powinien usuwać stare dane dokładnie tego źródła, a nie wszystkie dane ręczne lub z innego źródła.
5. Nie traktuj brakujących wymaganych pól/malformowanego JSON jako udanej pustej listy.

Akceptacja: awaria jednego okresu/źródła nie usuwa innych rekordów; prawidłowe wycofanie wszystkich ocen nie pozostawia „duchów”.

### A08 — P1: import z powiadomień nie odróżnia pochodzenia i generuje niepewne dane

**BAZA i OTWARTE.** `EduVulcanNotificationListener`: `manual_notif_` jest uznawany za dane ręczne przez filtr `manual_`; odłączenie konta może je zachować, import z API może je dublować. Fallback regex może uznać dowolną cyfrę 1–6 z tekstu za ocenę. Data wydarzenia bez rozpoznanej daty jest ustawiana na jutro. Przy skanowaniu starych aktywnych powiadomień względna data liczona jest od dzisiejszego dnia, nie `sbn.postTime`. ID oparte na 32-bit `hashCode()` mają możliwość kolizji.

1. Wprowadź jawne `source` i `account/profileId`, nie semantykę zakodowaną w prefiksie ID.
2. Oddziel dane wpisane przez użytkownika od provisional notification imports. Deduplikuj z API lub nie dodawaj takich ocen do oficjalnej średniej przed potwierdzeniem.
3. Usuń rozpoznawanie przypadkowej cyfry jako oceny i zgadywanie daty „jutro”. Niepewny import prezentuj do potwierdzenia albo pomiń.
4. Podaj parserowi Clock, ZoneId i czas publikacji powiadomienia. Dodaj test starych „jutro/pojutrze”, braku daty, kilku dat i przełomu roku.
5. Użyj stabilnego klucza powiadomienia lub pełnego digestu z namespace/profile, nie `String.hashCode()` jako jedynej tożsamości.
6. Anuluj scope w `onDestroy`, obsłuż wyjątki każdego zapisu i serializuj/debounce lawinę powiadomień. `runCatching` w suspend ścieżkach nie może połykać anulowania.
7. Uzgodnij jawny przełącznik importu i zachowanie po odłączeniu konta; dostępu systemowego do powiadomień nie da się automatycznie „cofnąć” samym usunięciem sesji.

Akceptacja: fałszywa ocena/data nie zmienia wyników; odłączenie usuwa importy zgodnie z deklaracją; ponowny skan nie duplikuje danych.

### A09 — P1: trwałe dane użytkownika traktowane jak cache

**BAZA i OTWARTE.** `DatabaseModule` pozwala na destructive migration przy downgrade. Backup XML wyklucza bazę Room, ale Room zawiera nie tylko cache: również aktywności użytkownika i ręczne oceny. Reinstalacja/transfer/downgrade może utracić dane, których nie odtworzy synchronizacja.

1. Sporządź listę tabel: cache vs dane nieodtwarzalne. Usuń komentarze, że cała baza jest cache.
2. Wprowadź osobny trwały backup/export danych ręcznych z wersjonowaniem i walidacją albo rozdziel trwałe dane i cache do różnych magazynów.
3. Nie włączaj backupu całej bazy bez projektu prywatności: zawiera też importy ucznia.
4. Downgrade odrzucaj z czytelną instrukcją lub obsłuż jawnie; nie kasuj ręcznych danych jako efekt uboczny.
5. Dodaj test odtworzenia na API ≤30 oraz ≥31, obecności własnych ocen/aktywności i nieobecności sekretów. Zachowaj wykluczenie WebView i calendar preferences.

Akceptacja: udokumentowana i przetestowana możliwość zachowania danych ręcznych; brak przypadkowego transferu cookies/hasła. Zobacz oficjalne [zasady Android Auto Backup](https://developer.android.com/identity/data/autobackup).

### A10 — P1/P2: niekonsekwentne anulowanie żądań i zamykanie odpowiedzi

**BAZA i OTWARTE.** `VulcanApiClient` używa wielu synchronicznych `execute()`, szerokiego `runCatching` oraz `continue` przed odczytem/zamknięciem nieudanej odpowiedzi (np. statystyki frekwencji, oceny, `fetchDetailJson`). `CalendarRepositoryImpl.fetchIcalEvents` także wykonuje blocking call. Sam `Dispatchers.IO` nie wiąże anulowania korutyny z `Call.cancel()`.

1. Zastosuj istniejący `data/remote/http/readCancellable` lub równoważny adapter w każdej ścieżce. Odpowiedź obejmij `use`, także dla statusów 4xx/5xx, pustego body i błędu parsera.
2. Przekształć potrzebne metody na suspend; propaguj `CancellationException`, nie zamieniaj jej na zwykły `Result.failure`.
3. Nie ponawiaj operacji o skutkach ubocznych bez oceny idempotencji. Zweryfikuj retry dla rejestracji urządzenia, nie tylko GET.
4. MockWebServer: opóźniona odpowiedź, anulowanie, 500 z body, przerwanie odczytu. Sprawdź zakończenie call i brak zapisu po anulowaniu.

Akceptacja: timeout/anulowanie kończy realne HTTP, nie tylko oczekiwanie UI; brak narastających otwartych response body. Zasada kooperacyjnego anulowania: [Kotlin documentation](https://kotlinlang.org/docs/cancellation-and-timeouts.html).

### A11 — P2: brak limitów wielkości odpowiedzi i pobierania APK

**OTWARTE.** `ResponseBody.string()` w Vulcan, iCal i release API wczytuje całość. `ApkUpdateInstaller.download` zapisuje stream bez twardego limitu; download client ma celowo `callTimeout(0)`. Złośliwa lub błędna odpowiedź może wyczerpać RAM/dysk. `TransitStopCatalog` ma już limit 2 MB — nie zgłaszaj go jako miejsca bez limitu.

1. Ustal konfigurowalne limity osobno dla JSON, ICS, HTML i APK; uwzględnij rozmiary rzeczywistych danych, nie arbitralne małe wartości.
2. Sprawdzaj `Content-Length`, ale również licz rzeczywiste bajty dla chunked/gzip/nieprawdziwego nagłówka.
3. Dodaj budżet całej operacji pobierania, możliwość anulowania i bezpieczne sprzątanie `.part`.
4. Testuj przekroczenie limitu z i bez Content-Length, wolny nieskończony stream, brak miejsca na dysku.

Akceptacja: kontrolowany błąd bez OOM/zapełnienia dysku i bez usunięcia poprawnych danych cache.

### A12 — P2: aktualizator APK ma niepełną walidację artefaktu i błędów instalacji

**BAZA i OTWARTE.** `GitHubReleases.apkAsset` preferuje release, lecz akceptuje debug jako fallback. `ApkUpdateInstaller` sprawdza packageName/versionCode i opcjonalną SHA-256, ale nie sprawdza signer przed otwarciem instalatora. Android sam kontroluje podpis przy aktualizacji; brak precheck nie jest sam w sobie dowodem zdalnego wykonania kodu. Błąd `startActivity` instalatora kończy się logiem przy stanie `ReadyToInstall`.

1. Odrzuć debug assets, ustal jednoznaczny produkcyjny asset i nie wybieraj losowego pierwszego `.apk` przy wielu wariantach.
2. Parsuj URL jako URI/HttpUrl i weryfikuj HTTPS, host, repo/path oraz politykę przekierowań do właściwego hostingu GitHub. Nie wyłączaj niezbędnych CDN redirectów bez testu.
3. Jeśli digest jest podany, wymagaj poprawnego formatu i zgodności. Oddziel checksum od zaufania do wydawcy.
4. Sprawdź zgodność signer/lineage z zainstalowaną aplikacją, obsługując API 26–27 i nowsze; nie koduj na stałe nowego certyfikatu bez zgody na rotację. [SigningInfo](https://developer.android.com/reference/android/content/pm/SigningInfo) opisuje także historię certyfikatów.
5. Błąd otwarcia ustawień/instalatora powinien przechodzić do Failed z instrukcją, nie pozostawać w Ready/NeedsPermission bez wyjścia.
6. Testuj obcy certyfikat, niższy versionCode, brak APK, debug-only release, zły hash, odmowę uprawnień i brak handlera Intent.

Akceptacja: tylko właściwy asset, czytelny błąd zamiast pozornego sukcesu, test aktualizacji z istniejącej instalacji bez utraty danych.

### A13 — P1/P2: raporty diagnostyczne mogą ujawniać prywatne dane

**BAZA i OTWARTE.** `CrashReport.filterLogs` zachowuje wszystkie W/E/F/A, także tagi spoza allowlist. Pomijanie tagów Transit w `Diagnostics.APP_TAGS` nie usuwa ich ostrzeżeń/błędów. `SafeUrls` loguje cały odrzucony URL; `SyncCoordinator` przekazuje wyjątki do logów; raport może trafić do publicznego GitHub issue. Nie wykazano w tym audycie konkretnego wycieku produkcyjnego hasła, ale obecna polityka filtrowania nie gwarantuje deklarowanej prywatności.

1. Zbuduj redaktor URL query/userinfo, Cookie/Authorization, identyfikatorów ucznia, adresów i sekretów; stosuj go do logów, details i stacktrace przed zapisaniem/udostępnieniem.
2. Zastąp pełne URL i exception body bezpieczną kategorią/status code. Nie polegaj tylko na wyłączonym HTTP loggerze.
3. Dodaj fixtures syntetycznych sekretów w logach W/E, stacktrace, wieloliniowych wiadomościach i URL kodowanych procentowo.
4. Przed udostępnieniem daj podgląd oraz ostrzeżenie o publiczności Issues. Nie wysyłaj raportów automatycznie.

Akceptacja: żaden znacznik syntetycznego sekretu nie występuje w finalnym raporcie; logi transportu nie obchodzą filtra.

### A14 — P1/P2: schema 6, migracje i zapis atomowy

**BAZA; LOKALNIE częściowo naprawione.** Baza jest w wersji 6, a bazowy Git zawiera schema JSON 2–5. Przy lokalnych testach ujawniono problem nadpisywania `AtomicFile` na Windows/Robolectric oraz stan WorkManager testów.

1. Wygeneruj i zatwierdź schema 6 z KSP. Nie przepisuj historycznych JSON-ów i nie ręcznie dopasowuj identityHash.
2. Testuj pełną walidację Room oraz zachowanie danych dla każdej obsługiwanej wersji startowej; istniejące 2→6 i 5→6 uzupełnij, jeśli inne ścieżki nie są pokryte.
3. Kontroluj w CI brak zmian i untracked plików w `app/schemas` po buildzie.
4. Lokalny `AtomicDiskWrite.kt` używa fsync + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`. Zweryfikuj zachowanie na Android API 26/35: overwrite, crash przed/po move, stary `.bak`, brak miejsca i współbieżność. Sukces na Windows nie dowodzi trwałości na wszystkich Android FS.
5. Zapisuj ulubione i cache przez jedną testowaną strategię; błąd zapisu nie może udawać sukcesu ani usunąć starego poprawnego pliku.

Akceptacja: migracja zachowuje ręczne dane i ulubione, a awaria zapisu pozostawia poprzednią wersję pliku.

### A15 — P1/P2: CI i release nie stanowią jeszcze kompletnej bramki wydania

**BAZA; LOKALNIE rozszerzone.** Nowy CI testuje Github/Gms debug, build release z R8 oraz lint release; actions przypięte do SHA, Dependabot dodany. Release workflow buduje Github, weryfikuje tag, notes i podpis. Ustawienie `environment: release` samo nie tworzy ochrony/sekretów. CI build może być unsigned; to nie produkcyjna weryfikacja podpisu.

1. Przenieś lokalne workflowy po przeglądzie i uruchom je w GitHub Actions. Lokalne actionlint nie zastępuje rzeczywistego runnera.
2. Uzależnij wydanie od sprawdzenia testów/lint/R8 dla tego samego SHA. Release obecnie uruchamia testy Github, ale nie własny lint ani potwierdzenie wyniku całej macierzy.
3. Sprawdź monotoniczny versionCode, zgodność versionName z tagiem i dokładnie jeden właściwy APK. Usuń publikowanie debug.
4. Ogranicz `contents: write` do joba publikującego, nie całego etapu build, jeśli to praktyczne; brak sekretów w PR od forków.
5. Taguj dopiero po konfiguracji podpisu i green checks. Nie uruchamiaj produkcyjnego wydania z testowym kluczem ani nie zmieniaj istniejących tagów/assetów w miejscu.
6. Przetestuj workflow na kontrolowanym prerelease, z owner-approved signer. Brak sekretu ma kończyć job przed publikacją.

Akceptacja: rzeczywisty green run na publikowanym SHA, hash/fingerprint/versionCode udokumentowane, brak debug/test-signed APK w produkcyjnym wydaniu.

### A16 — P2: warianty dystrybucji i konfiguracja GMS

**BAZA; LOKALNIE porządkowane.** Historyczne FOSS/GMS i F-Droid są sprzeczne z obecną decyzją właściciela. Lokalnie: `github` bez Firebase, `gms` opcjonalny, wspólny aktualizator i provider. `-Pelektron.gms=false` pozwala testować kompilację GMS bez produkcyjnego `google-services.json`; nie dowodzi działania FCM.

1. Utrzymaj tylko aktywne kanały GitHub; historycznych changelogów nie fałszuj przez usuwanie dawnych informacji.
2. Testuj świeży clone, Gradle sync IDE, pojedyncze i mieszane taski flavorów oraz ogólne `assemble`.
3. Nie uzależniaj bezpieczeństwa od heurystyki nazwy taska; udokumentuj jawny przełącznik i błąd brakującej konfiguracji GMS.
4. Sprawdź merged manifest obu wariantów: FileProvider nieexportowany, wąski cache path, instalacja APK tylko w odpowiednich manifestach, bez niepotrzebnych usług Firebase w Github.

Akceptacja: domyślny Github działa bez Google config, opcjonalny GMS z poprawną konfiguracją działa na urządzeniu.

### A17 — P2: obsługa FCM i realne zachowanie pracy w tle

**BAZA; LOKALNIE usunięty runBlocking z callbacku FCM.** `src/gms/.../ElektronFirebaseMessagingService.kt` ma kolejkę `PushMessageWorker` i osobny handler.

1. Callback ma wyłącznie walidować ograniczony payload i enqueue; żadnego blocking network/DB.
2. Testuj worker retry/backoff, anulowanie, zduplikowane wiadomości, oversize/unknown payload oraz nadrabianie po zabiciu procesu.
3. Dobierz politykę unique work/debounce i idempotencję powiadomień. Zmierzyć opóźnienia FCM i WorkManager na realnym GMS, nie tylko test handlera.
4. Testuj alarmy/notatki/DND/widget: denied POST_NOTIFICATIONS, exact alarm revoked, Doze, reboot, DST/timezone, bateria/OEM, brak network. Nie nazywaj testu JVM testem end-to-end systemowego alarmu.
5. Auto DND musi przywrócić wcześniejszy stan i respektować ręczną zmianę użytkownika; manualna próba wymaga fizycznego urządzenia.

Akceptacja: brak długiego callbacku/ANR, idempotentne wiadomości i udokumentowana macierz testów urządzeń.

### A18 — P2: parser iCal ma ograniczoną semantykę czasu

**OTWARTE/WERYFIKACJA kontraktu.** `IcalParser` używa lokalnych dat/godzin i odrzuca parametry w dodatkowych kluczach; sprawdź dolną część `parseDateTime` przed zmianą. Nie zakładaj wsparcia TZID/UTC/recurrence tylko dlatego, że proste daty się parsują. UID bez wartości jest zastępowany losowym UUID, więc ten sam niekompletny feed ma niestabilną tożsamość.

1. Ustal wymagany podzbiór iCalendar: TZID, `Z`, VALUE=DATE, exclusive DTEND, RRULE/EXDATE, STATUS:CANCELLED i folded lines.
2. Dodaj fixtures dla UTC→Europe/Warsaw, DST, całodniowego wydarzenia, powtórzeń i anulowania. Oznacz unsupported, zamiast prezentować niepoprawną godzinę.
3. Zapewnij deterministyczny fallback ID dla wydarzeń bez UID i scoping do źródła.
4. Nie wdrażaj pełnego RFC ręcznie, jeśli utrzymywana biblioteka lepiej pasuje; osobno oceń zależność/licencję.

Akceptacja: jawny kontrakt parsera i testy wymaganych formatów; powtórny sync nie tworzy nowych ID dla tego samego wydarzenia.

### A19 — P2: zależności, lint i powtarzalność buildów

**OTWARTE.** Wersje z katalogu nie są dowodem, że są bezpieczne/aktualne. Lint zgłasza m.in. stare zależności i hardcoded strings. Nie ustalono w tym audycie pełnej listy CVE, więc nie wpisuj zmyślonych identyfikatorów.

1. Zapisz dependency tree obu runtimeClasspath i sprawdź faktycznie rozwiązywane wersje w oficjalnych advisory/release notes, nie tylko deklaracje TOML.
2. Ustal kompatybilną macierz AGP/Gradle/JDK/Kotlin/KSP/Compose. Upgrade narzędzi i feature patch rozdziel do małych commitów.
3. Zabezpiecz wrapper checksum i rozważ Gradle dependency verification/lock zgodnie z realnymi pluginami.
4. Rozdziel lint: naprawialne ostrzeżenia, świadome odstępstwa i wersje testowe. Nie baseline'uj wszystkiego, aby stworzyć sztuczną „czystość”.
5. Sprawdź licencje i obowiązki atrybucji map/OSM/fontów/ikon/bibliotek; aktualny stan licencji nie został tutaj kompletnie zweryfikowany.

Akceptacja: udokumentowane wersje i źródła advisory, reproducible clean build, brak globalnego suppression nowych problemów.

### A20 — P2/P3: UI, dostępność i dokumentacja

**BAZA; LOKALNIE częściowe porządki.** UI jest polskie, metadane en-US nie oznaczają angielskiego UI. Dwa lokalne testy Compose logowania nie pokrywają całego produktu.

1. Przenieś aktywne hardcoded strings do zasobów PL; nie obiecuj tłumaczenia EN, jeśli go nie ma.
2. Dodaj testy ekranów: loading/error/empty/stale, brak konta, odłączanie, błąd Keystore, demo, niepełny import, odmowa uprawnień, duża czcionka i TalkBack.
3. Sprawdź opis uprawnień i prywatności: Room nie jest w całości szyfrowany; hasło pamiętane; notification access szeroki; raporty udostępnia użytkownik. Nie deklaruj zgodności prawnej bez osobnej oceny.
4. Utrzymaj `SECURITY.md`, `CONTRIBUTING.md`, hooks setup, schema version, prawidłowe build commands i release notes per tag.
5. Usuń tylko duplikaty screenshotów po porównaniu hash; lokalnie usunięto kopie `docs/screenshots` i zachowano oryginały fastlane. Dopisz historyczne changelogi tylko na podstawie tagów.
6. Twierdzenie poprzedniego audytu o repo z jednym commitem było błędne; historia zawiera wiele commitów. Nie podejmuj „naprawy historii” w odpowiedzi na to ustalenie.

Akceptacja: dokumentacja odpowiada rzeczywistemu APK, kluczowe stany UI mają testy i ręczną weryfikację.

## 5. Kolejność pracy — step-by-step

1. **Inwentaryzacja:** `git status`, `git log`, `git diff`, aktualny HEAD, merged manifesty, obecność nowych plików. Zachowaj niezwiązane zmiany. Nie używaj `reset --hard`.
2. **Bezpieczna baza:** A01/A02/A04. Najpierw podpis/sekrety, migracja poświadczeń i WebView. Jeśli już wdrożone, przegląd + testy zamiast drugiej implementacji.
3. **Prawdziwość danych:** A06/A07/A08. Usuń ukryte demo, niepełne snapshoty i niepewne importy wpływające na średnie.
4. **Własność i trwałość:** A05/A09/A14. Scoping profile/source, transakcje, migracje, export ręcznych danych. Każda zmiana schema wymaga kolejnej wersji i nowych testów.
5. **Awaria i anulowanie:** A03/A10/A11/A13. Recovery, bezpieczne raporty, ograniczenia streamów i cancellable HTTP.
6. **Dystrybucja:** A12/A15/A16. Jeden właściwy asset, zweryfikowany signer, release gate, działający clean clone obu wariantów.
7. **Urządzenie:** A17/A18/A20. Osobne testy live auth, backup, Doze, DND, parser czasu i dostępność. Nie wpisuj PASS bez wykonania.
8. **Utrzymanie:** A19 i pozostałe docs/lint. Zależności aktualizuj partiami z testami po każdej zmianie.
9. **Publikacja:** nowy versionName/versionCode, nowe release notes, commit, green CI, zatwierdzony klucz produkcyjny, nowy tag. Żadnego nadpisywania istniejącego wydania. Audyt zostaje w repo, nie w assets.
10. **Handoff:** dla każdego Axx dopisz commit, test command, wynik, brakujące próby i owner blockers. Nie usuwaj śladu incydentu podpisu dlatego, że build przechodzi.

## 6. Komendy i bramki końcowe

Po wdrożeniu lokalnej zmiany flavorów (bazowy commit ma jeszcze `Foss`, nie `Github`):

```powershell
# PowerShell: przełącznik z kropką przekazuj w cudzysłowie.
.\gradlew.bat --no-daemon --no-parallel '-Pelektron.gms=false' :app:testGithubDebugUnitTest :app:testGmsDebugUnitTest
.\gradlew.bat --no-daemon --no-parallel '-Pelektron.gms=false' :app:assembleGithubRelease :app:assembleGmsRelease
.\gradlew.bat --no-daemon --no-parallel '-Pelektron.gms=false' :app:lintGithubRelease :app:lintGmsRelease
git diff --check
python .github/check_repository_files.py
git diff --exit-code -- app/schemas
git ls-files --others --exclude-standard app/schemas
```

Kontroler higieny sprawdza indeks Git: stage świadomie wybrane pliki/delecje przed sprawdzeniem kandydata. Nie rób `git add -A`, jeśli istnieją cudze niezwiązane zmiany. GMS bez Google config służy testom kompilacji, nie publikacji funkcjonującego FCM. JDK 21, Android SDK/platform 35 i wymagane build-tools powinny być dostępne; nie polegaj na lokalnym SDK innego komputera.

Nowe wymagane testy mogą nazywać się np. `AttendanceSyncFailureTest`, `AccountSwitchRaceTest`, `PartialSnapshotTest`, `NotificationImportProvenanceTest`, `CredentialRecoveryTest`, `HttpCancellationTest`, `ResponseLimitTest`, `CrashRedactionTest`, `UpdateInstallerValidationTest` i `UserDataRestoreTest`. To proponowane nazwy zadań, nie zapewnienie, że te testy już istnieją.

Przed produkcyjnym wydaniem sprawdź:

- [ ] Każda P0/P1 ma poprawkę albo jawną decyzję właściciela o ryzyku; nie ukrywaj otwartych problemów.
- [ ] Schematy/migracje bez utraty ręcznych danych.
- [ ] Wszystkie testy obu utrzymywanych wariantów, release R8 i lint zakończone.
- [ ] Test starej instalacji → nowy APK, bez reinstalacji.
- [ ] Certyfikat produkcyjny, zgodny packageName, rosnący versionCode, tag zgodny z versionName.
- [ ] Secrets/protected environment zweryfikowane na GitHubie; klucz nieobecny w nowych plikach.
- [ ] APK nie jest debug ani podpisany testowo; checksum i fingerprint zapisane w release notes.
- [ ] Release nie zawiera audytu, sekretów ani danych prywatnych.
- [ ] Rzeczywista lista niewykonanych testów manualnych jest jawna.

## 7. Gotowe polecenie dla następnego modelu

> Przeczytaj AUDYT_PROJEKTU_2026-10-13.md i instrukcje AGENTS.md, jeśli istnieją. Porównaj aktualny HEAD z bazą 7c66ee5 i ustal, które lokalnie opisane poprawki rzeczywiście są już w repo. Nie dubluj wykonanych napraw. Naprawiaj A01–A20 według kolejności w sekcji 5, zaczynając od testów reprodukujących. Nie rotuj klucza, nie przepisuj historii, nie publikuj klucza testowego i nie załączaj audytu w Releases. Zachowaj GitHub-only dystrybucję i szyfrowane zapamiętywanie hasła. Nie traktuj danych demo/niepełnych snapshotów jako rzeczywistych danych ucznia. Każdą poprawkę sprawdź, zapisz wyniki i osobno wymień niewykonane próby na urządzeniu. Operacje wymagające właściciela GitHub lub decyzji o podpisie zgłoś jawnie, zamiast pozorować ukończenie.
