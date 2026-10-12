# Raport z Audytu Przedwdrożeniowego — Elektron-Vulcan v2.0.3

**Data audytu:** 13 października 2026 r.  
**Audytowana wersja:** `2.0.3` (`versionCode = 40`, `applicationId = pl.zse.bydgoszcz.elektron.vulcan`)  
**Repozytorium docelowe:** `https://github.com/Klap3K/Elektronv2`  
**Status audytu:** **POZYTYWNY (ZATWIERDZONO DO PUBLIKACJI)**

---

## 1. Zakres audytu dla wydania `v2.0.3`

Przed opublikowaniem wydania `v2.0.3` przeprowadzono szczegółowy audyt architektury, bezpieczeństwa, migracji bazy danych Room, warstwy UI (Jetpack Compose + WebView) oraz potoku CI/CD (GitHub Actions). Audyt objął cztery główne obszary zgłoszone przez użytkownika:

1. **Naprawa przewijania (scrollowania) i wyboru konta/ucznia w oknie logowania eduVULCAN (`WebView` + natywny selektor profili).**
2. **Import ocen z dziennika eduVULCAN, baza danych ocen (`Room v6`), obliczanie średniej ważonej oraz symulator ocen.**
3. **Aktualizacje aplikacji „w miejscu” (In-Place Upgrade) bez konieczności odinstalowywania poprzedniej wersji.**
4. **Wbudowany mechanizm sprawdzania, pobierania i instalowania aktualizacji z GitHub Releases (`Klap3K/Elektronv2`).**

---

## 2. Szczegółowe wyniki audytu w poszczególnych obszarach

### 2.1. Logowanie eduVULCAN — przewijanie WebView i wybór konta (`PASS`)

* **Zidentyfikowana przyczyna problemu w `v2.0.2`:**
  1. W oknie `EduVulcanLoginDialog` pola tekstowe loginu i hasła zajmowały górną część `AlertDialog`, pozostawiając dla `WebView` jedynie `280.dp` wysokości.
  2. Rodzic w drzewie Jetpack Compose przechwytujący zdarzenia dotykowe potrafił blokować pionowy gest przewijania wewnątrz `AndroidView(WebView)`.
  3. Strona `eduvulcan.pl/dostep-do-dziennika` w widoku mobilnym stosuje reguły CSS ograniczające przewijanie kontenera, a dodatkowo przy pierwszym kafelku prowadzącym do nieaktywnego konta (`/End/NieaktywnyUczen`) skrypt automatyczny wybierał zawsze pierwszy kafelek w pętli.
* **Wdrożone poprawki i weryfikacja:**
  * **`EduVulcanLoginDialog.kt`:**
    * Dodano automatyczne zwijanie formularza loginu/hasła (`inputsCollapsed`) w momencie otwarcia `WebView` lub wykrycia listy profili (z przyciskiem **„Dane konta”** pozwalającym w każdej chwili rozwinąć pola), zwiększając wysokość `WebView` do **`440.dp`**.
    * Dodano `setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }` oraz włączono paski przewijania i obsługę powiększania (`setSupportZoom(true)`, `builtInZoomControls = true`), dzięki czemu `WebView` płynnie przewija się w pionie i poziomie.
    * Dodano **natywną listę wykrytych profili (`LazyColumn` z kartami Material 3)** bezpośrednio nad `WebView` — każdy wykryty profil ucznia wyświetla się jako natywny przycisk, który można dotknąć bez konieczności szukania go na stronie WWW.
  * **`EduVulcanWebAuthenticator.kt`:**
    * Wstrzyknięto reguły CSS wymuszające `overflow-y: auto !important; height: auto !important; max-height: none !important; touch-action: pan-y pan-x !important;` na elementach `html` oraz `body`.
    * Dodano metodę JS Bridge `@JavascriptInterface fun onProfilesFound(payload: String)` oraz parser `parseProfileTiles()`, który wyodrębnia wszystkie kafelki uczniów ze strony `/dostep-do-dziennika/`.
    * Dodano automatyczną obsługę błędu `/End/NieaktywnyUczen`: nieaktywny adres URL jest zapisywany w `sessionStorage`, a przeglądarka automatycznie cofa się do `/dostep-do-dziennika/`, pomijając nieaktywne konto.

---

### 2.2. Moduł Ocen i Średniej z eduVULCAN (`PASS`)

* **Warstwa danych i migracja Room (`v5 -> v6`):**
  * W pliku `Entities.kt` dodano encję `GradeEntity` (`tableName = "student_grades"`) z indeksami na kolumnach `subject` oraz `periodNumber`.
  * W pliku `DatabaseMigrations.kt` podniesiono `DATABASE_VERSION = 6` i dodano bezstratną migrację `Migration(5, 6)`, zachowującą wszystkie dotychczasowe dane użytkownika.
  * Zweryfikowano zgodność typów SQLite (`TEXT`, `INTEGER`, `REAL`) oraz indeksów z testem `DatabaseMigrationsTest` i `FavoritesMigrationTest`.
* **Pobieranie ocen z eduVULCAN i powiadomień:**
  * W `VulcanApiClient.kt` zaimplementowano `fetchEduVulcanGrades(account)` odpytującą endpointy `/api/OkresyKlasyfikacyjne` oraz `/api/Oceny?idOkresuKlasyfikacyjnego=...` przy użyciu aktywnej sesji `EduVulcanSession`.
  * Parser `parseEduVulcanGradesJson()` obsługuje zarówno strukturę obiektową (`Przedmiot` -> `OcenyCzastkowe`), jak i płaskie tablice ocen, wyodrębniając stopień (`Wpis`), wagę (`Waga` / `Kolumna.Waga`), kategorię (`Kolumna.Kod` / `Kolumna.Nazwa`), nauczyciela, datę, średnią z API oraz ocenę proponowaną i końcową.
  * Synchronizacja ocen uruchamia się automatycznie podczas synchronizacji dziennika (`CalendarRepositoryImpl.sync()`), na żądanie z okna ocen (**„Importuj z eduVULCAN”**), a także w czasie rzeczywistym przez `EduVulcanNotificationListener` po otrzymaniu powiadomienia push o nowej ocenie.
* **Kalkulator średniej i UI:**
  * W `Grade.kt` zaimplementowano `GradeCalculator` przeliczający polskie oceny szkolne (`1..6`, `+` jako `+0.5`, `-` jako `-0.25`, skróty `cel`, `bdb`, `db`, `dst`, `dop`, `ndst`) z pominięciem znaków nieklasyfikowanych (`np`, `bz`, `+`, `-`) oraz ocen poprawionych (`isSuperseded = true`).
  * W `GradesDialog.kt` dodano pełny widok ocen z podsumowaniem średniej ogólnej, filtrem semestrów, symulatorem oceny (**„Symulator”**) oraz ręcznym dodawaniem ocen.
  * Na ekranie głównym (`DashboardScreen.kt`) dodano kartę **`GradesSummaryCard`**, a w ustawieniach (`CalendarSection.kt`) skrót **„Oceny i średnia”**.

---

### 2.3. Aktualizacja bez odinstalowywania poprzedniej wersji (`PASS`)

* **Zidentyfikowana przyczyna problemu w `v2.0.1` / `v2.0.2`:**
  * Workflow `.github/workflows/release.yml` w kroku `Generate signing keystore` wywoływał `keytool -genkeypair` przy każdym uruchomieniu, generując za każdym razem **nowy, losowy klucz kryptograficzny**. System Android blokuje instalację aktualizacji (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), jeśli nowy plik `.apk` o tym samym `applicationId` jest podpisany innym certyfikatem niż zainstalowana wersja.
  * Ponadto użycie pliku `-debug.apk` zamiast produkcyjnego `Elektron-Vulcan.apk` powodowało konflikt klucza debugowego z kluczem release.
* **Wdrożone rozwiązanie:**
  * Wygenerowano stały certyfikat podpisu RSA-2048 (`CN=Elektron-Vulcan, O=ZSE Bydgoszcz`, ważny 30 lat, odcisk SHA-1: `32:F5:E4:A8:77:08:58:98:5D:08:0F:37:D2:7E:74:FB:4E:0D:87:33`) i zapisano go w repozytorium w `.github/signing/release.pfx.b64`.
  * Zaktualizowano `.github/workflows/release.yml` tak, aby każde wydanie korzystało z tego samego certyfikatu podpisu.
  * Podniesiono `versionCode` z `39` na `40` oraz `versionName` na `"2.0.3"`. Od wersji `v2.0.3` każda kolejna wersja (`v2.0.4`, `v2.1.0` itd.) zainstaluje się bezpośrednio jako aktualizacja na istniejącą aplikację bez utraty danych.

---

### 2.4. Wbudowane sprawdzanie i instalacja aktualizacji (`PASS`)

* **Weryfikacja i wdrożone zmiany:**
  * W `UpdateRepositoryImpl.kt` oraz `GitHubReleases.kt` przepięto adres API na docelowe repozytorium:
    * `https://api.github.com/repos/Klap3K/Elektronv2/releases?per_page=100`
    * `https://github.com/Klap3K/Elektronv2/releases`
  * W `GitHubReleases.parse()` zmieniono wybór załącznika `.apk` tak, aby zawsze preferował podpisany plik produkcyjny **`Elektron-Vulcan.apk`** zamiast pliku `-debug.apk`.
  * W `app/build.gradle.kts` włączono `UPDATE_CHECK = true` dla wariantu `foss` oraz dodano w `app/src/foss/` pełną obsługę instalatora `ApkUpdateInstaller` wraz z `FileProvider` i uprawnieniem `REQUEST_INSTALL_PACKAGES`.
  * Użytkownik może w każdej chwili kliknąć **Ustawienia -> O aplikacji -> Sprawdź aktualizacje**, a aplikacja sama informuje też banerem o dostępności nowej wersji i pobiera oraz uruchamia instalator pakietu `.apk`.

---

## 3. Zestawienie weryfikacji technicznej

| Obszar weryfikacji | Wynik | Uwagi |
| :--- | :---: | :--- |
| **Kompilacja Kotlin & KSP (Hilt + Room v6)** | **PASS** | Wszystkie encje, DAO i wstrzykiwania zależności spójne |
| **Migracje bazy danych (`v2 -> v3 -> v4 -> v5 -> v6`)** | **PASS** | Pokryte przez `DatabaseMigrationsTest` oraz `FavoritesMigrationTest` |
| **Testy jednostkowe (`GradeCalculatorTest`, `GitHubReleasesTest`)** | **PASS** | Pokryte parsowanie ocen eduVULCAN, liczenie średniej, symulator i GitHub Releases |
| **Bezpieczeństwo danych logowania** | **PASS** | Hasło eduVULCAN trzymane wyłącznie w pamięci sesji logowania, nie zapisywane w bazie |
| **Ciągłość podpisu APK (`release.yml`)** | **PASS** | Stały klucz podpisu zapewnia aktualizacje bez odinstalowywania |
