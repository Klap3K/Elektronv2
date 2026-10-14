# Poradnik: powiadomienia push

Powiadomienia push skracają czas, po którym aplikacja dowiaduje się o nowym zastępstwie albo
ogłoszeniu: zamiast czekać na najbliższą synchronizację w tle (WorkManager, najczęściej co 15 minut),
telefon dostaje sygnał w ciągu kilku minut od zmiany na stronie szkoły.

Dotyczy to tylko opcjonalnego wariantu **`gms`** z własną konfiguracją Firebase. Standardowy APK z GitHub Releases (`github`) nie ma
Firebase i polega wyłącznie na synchronizacji w tle. Nie potrzebuje niczego z tego poradnika.

Całość działa na darmowej infrastrukturze, bez własnego serwera:

| Element | Gdzie | Rola |
|---|---|---|
| Firebase Cloud Messaging | projekt w konsoli Firebase | dostarcza wiadomości na telefony |
| Watcher | Repozytorium watchera push | co 5 minut sprawdza stronę szkoły i wysyła push |
| Zegar | [cron-job.org](https://cron-job.org) | uruchamia watcher co 5 minut |
| Aplikacja | `app/src/gms` | subskrybuje tematy i odbiera wiadomości |

Oficjalna konfiguracja już działa. Ten poradnik opisuje, jak postawić ją od zera, np. po utracie
dostępu do projektu Firebase albo we własnym forku.

---

## Krok 1. Projekt Firebase

1. Wejdź na **console.firebase.google.com** i zaloguj się kontem Google.
2. **Dodaj projekt**, np. `elektron-zse`. Google Analytics nie jest potrzebne.
3. W projekcie kliknij ikonę **Androida** („Dodaj aplikację”).
4. **Nazwa pakietu:** `pl.zse.bydgoszcz.elektron.vulcan`. Musi się zgadzać z `applicationId` w `app/build.gradle.kts`.
5. Pobierz **`google-services.json`** i wrzuć go do `app/` w repozytorium aplikacji (obok
   `app/build.gradle.kts`, **nie** do katalogu głównego).
6. Cloud Messaging jest włączony domyślnie.

`app/google-services.json` jest w `.gitignore`. **Nie commituj go.** Każdy, kto buduje wariant `gms`,
potrzebuje własnej kopii. Wariant `github` buduje się bez tego pliku.

## Krok 2. Klucz konta usługi

1. W konsoli Firebase: **⚙️ Ustawienia projektu → Konta usługi (Service accounts)**.
2. Zakładka „Firebase Admin SDK” → **Wygeneruj nowy klucz prywatny**. Pobierze się plik JSON.
3. Ten klucz pozwala wysyłać powiadomienia w imieniu projektu. **Nie wrzucaj go do żadnego
   repozytorium ani czatu.** Potrzebny jest tylko jako sekret GitHub w kroku 4. Po wklejeniu
   możesz usunąć plik z dysku.

## Krok 3. Repozytorium watchera

Watcher musi działać w **publicznym** repozytorium na GitHubie. Tylko wtedy GitHub Actions jest bez
limitu minut (prywatne repozytorium ma 2000 minut miesięcznie, a przebieg co 5 minut zużyje je
w kilka dni).

Najprościej utworzyć repozytorium z kodem watchera (np. skrypt Python/Node sprawdzający zmiany i wysyłający FCM).
W repozytorium GitHub domyślnie wyłącza workflowy. Wejdź w zakładkę **Actions** i je włącz.

Zanim watcher wyśle pierwszy push, wyczyść stan z oryginału, żeby fork zaczął od własnego snapshotu.
Zastąp zawartość `state.json` przez `{}` i zatwierdź zmianę.

## Krok 4. Sekret w GitHub Actions

1. W repozytorium watchera: **Settings → Secrets and variables → Actions → New repository secret**.
2. Nazwa: **`FCM_SERVICE_ACCOUNT_JSON`**.
3. Wartość: **cała zawartość** pliku JSON z kroku 2, razem z `{ }`.
4. Zapisz.

## Krok 5. Pierwsze uruchomienie

1. **Actions → eLektron push watcher → Run workflow** (gałąź `main`).
2. W logach kroku „Sprawdź zmiany i wyślij push” powinno być `Sparsowano X zastępstw`,
   `Sparsowano Y ogłoszeń RSS` i `Nowych zastępstw: …, nowych ogłoszeń: …`.
3. Pierwszy przebieg na pustym stanie **nie wysyła** powiadomień. Zapisuje tylko snapshot
   w `state.json` (pojawi się commit „chore: aktualizacja stanu watchera”). Kolejne przebiegi
   wysyłają już tylko nowości.

## Krok 6. Zegar co 5 minut (cron-job.org)

Workflow ma własny harmonogram (`schedule` co 5 minut), ale GitHub uruchamia go w praktyce co
15-25 minut. Dlatego główny zegar to cron-job.org, który co 5 minut wywołuje workflow przez API
GitHuba. Harmonogram GitHuba zostaje jako zapas.

1. **Token GitHub:** **Settings (profilu) → Developer settings → Personal access tokens →
   Fine-grained tokens → Generate new token**.
   - *Repository access:* **Only select repositories** → tylko repozytorium watchera.
   - *Permissions → Repository permissions → Actions:* **Read and write**. Nic więcej.
   - *Expiration:* najdłuższy dostępny termin. Zapisz w kalendarzu datę wygaśnięcia.
2. **Zadanie w cron-job.org** (darmowe konto):
   - URL: `https://api.github.com/repos/<LOGIN>/elektron-push-watcher/actions/workflows/watch.yml/dispatches`
   - Harmonogram: co 5 minut
   - *Advanced:* metoda **POST**, nagłówki:
     - `Authorization: Bearer <TOKEN>`
     - `Accept: application/vnd.github+json`
     - `X-GitHub-Api-Version: 2022-11-28`
   - Treść żądania: `{"ref":"main"}`
3. Zapisz i użyj **Test run**. Poprawna odpowiedź to **204 No Content**, a w zakładce Actions pojawia
   się nowy przebieg „workflow_dispatch”.

Token trzymaj tylko w cron-job.org. Gdy wygaśnie, wygeneruj nowy i podmień nagłówek.

## Krok 7. Budowanie aplikacji

Z `app/google-services.json` z kroku 1, w katalogu repozytorium aplikacji (wymagany JDK 17 lub nowszy):

```bash
./gradlew :app:assembleGmsDebug
```

Jeśli domyślna Java w systemie jest starsza, wskaż nowszy JDK przez `JAVA_HOME`, np.
`JAVA_HOME=/ścieżka/do/jdk-21 ./gradlew :app:assembleGmsDebug`.

Wydanie: `:app:assembleGmsRelease` (wymaga też `keystore.properties`, patrz README).
Wariant bez Firebase: `:app:assembleGithubDebug`, bez `google-services.json`.

## Krok 8. Sprawdzenie

1. Zainstaluj APK `gms` i wybierz klasę. Aplikacja przy każdym starcie subskrybuje tematy
   `elektron-subs-zse-bydgoszcz` i `elektron-anns-zse-bydgoszcz` (`FcmTopicManager`). Błąd
   subskrypcji widać w logcat po tagu `FcmTopicManager`.
2. W Actions sprawdź, że przebiegi pojawiają się co około 5 minut i są zielone.
3. Najpewniejszy test to prawdziwe nowe zastępstwo albo ogłoszenie. Watcher zaloguje
   `Nowych zastępstw: 1`, a telefon dostanie powiadomienie w ciągu kilku minut, także przy zamkniętej
   aplikacji. Ręczna edycja `state.json` nic nie da: aplikacja traktuje push tylko jako sygnał,
   sama sprawdza stronę szkoły i pomija zastępstwa, o których już powiadomiła.

---

## Jak to działa

```
cron-job.org (co 5 min) ──► GitHub Actions: watch.py
                                │  pobiera stronę zastępstw i RSS, porównuje ze state.json
                                ▼  (nowość?)
                  FCM: temat elektron-subs-… / elektron-anns-…
                                │
                                ▼
          ElektronFirebaseMessagingService (telefon, app/src/gms)
                                │  zastępstwo: czy dotyczy wybranej klasy? czy już powiadomione?
                                ▼
          synchronizacja zastępstw ze strony szkoły (limit 8 s, potem WorkManager)
                                │
                                ▼
          powiadomienie systemowe (te same reguły co przy zwykłej synchronizacji)
```

Push jest tylko sygnałem. O tym, czy pokazać powiadomienie, decyduje aktualny stan strony szkoły
i ustawienia w aplikacji (klasa, grupy, włączone powiadomienia). Synchronizacja w tle działa dalej
jako zabezpieczenie, gdy push nie dotrze (brak sieci, Doze, przerwa w działaniu watchera).

Szczegóły watchera (ponawianie, archiwum stron, heartbeat): zobacz dokumentację w repozytorium watchera.

## Koszty

- **Firebase Cloud Messaging:** darmowe, bez karty.
- **GitHub Actions:** bez limitu, dopóki repozytorium watchera jest publiczne.
- **cron-job.org:** darmowe.

## Gdy coś nie działa

- **Build `gms` zgłasza brak `google-services.json`:** plik musi leżeć w `app/google-services.json`.
- **Workflow czerwony z „Brak sekretu FCM_SERVICE_ACCOUNT_JSON”:** sekret nie istnieje albo ma inną nazwę.
- **Workflow czerwony przy tworzeniu tokenu albo z „FCM send błąd (401/403)”:** w sekrecie musi być
  cały plik JSON z kroku 2, a nie sam klucz prywatny.
- **cron-job.org pokazuje 401:** token wygasł albo jest błędnie wklejony. **404:** zły adres
  lub token nie ma dostępu do repozytorium. **422:** zła gałąź w `{"ref":"main"}`.
- **Przebiegi tylko co 15-25 minut:** cron-job.org nie działa, a odpala tylko zapasowy harmonogram GitHuba.
- **Harmonogram GitHuba wyłączony:** GitHub wyłącza go po 60 dniach bez commitów. Watcher zapisuje
  `heartbeat.txt` co 25 dni, ale gdyby harmonogram się wyłączył, włącz go w zakładce Actions.
- **Zielony workflow, a brak powiadomień:** sprawdź w aplikacji, czy powiadomienia są włączone, czy
  wybrana jest właściwa klasa i grupy, oraz logcat z tagiem `FcmTopicManager`. Upewnij się też, że
  na telefonie jest APK `gms`, a nie `github`, i że klucz z kroku 2 pochodzi z tego samego projektu
  Firebase co `google-services.json` użyty do zbudowania aplikacji.
