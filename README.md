<div align="center">

<img src="docs/logo.png" alt="Elektron-Vulcan" width="112">

# Elektron-Vulcan

**Nowoczesna aplikacja na Androida dla uczniów ZSE w Bydgoszczy – plan lekcji, zastępstwa, ogłoszenia, odjazdy, terminarz sprawdzianów i oceny z konta eduVULCAN, Strażnik Frekwencji oraz inteligentne funkcje szkolne.**

[![Licencja: GPL v3+](https://img.shields.io/badge/licencja-GPL--3.0--or--later-blue)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
[![eduVULCAN](https://img.shields.io/badge/eduVULCAN-Logowanie%20do%20konta-blueviolet)](#główne-funkcje)
[![Pobierz APK](https://img.shields.io/badge/Wydanie-v2.0.6-success?logo=android&logoColor=white)](https://github.com/Klap3K/Elektronv2/releases/tag/v2.0.6)

[**Pobierz najnowszą wersję (v2.0.6)**](https://github.com/Klap3K/Elektronv2/releases/tag/v2.0.6) • [**Poradnik eduVULCAN**](PORADNIK_VULCAN.md) • [**Polityka Prywatności**](PRYWATNOSC.md) • [**Zgłoś błąd / Sugestie**](https://github.com/Klap3K/Elektronv2/issues/new)

</div>

---

## O projekcie Elektron-Vulcan

**Elektron-Vulcan** to zaawansowana, w pełni otwartoźródłowa aplikacja mobilna stworzona z myślą o uczniach Zespołu Szkół Elektronicznych w Bydgoszczy. Aplikacja gromadzi w jednym, przejrzystym miejscu wszystkie informacje niezbędne w szkolnej codzienności: od planu lekcji i zmian sal, przez ogłoszenia i rozkłady odjazdów ZDMiKP, aż po **obsługę technologii bezpośredniego logowania do konta eduVULCAN** oraz narzędzia wspomagające naukę i organizację roku szkolnego.

### Najważniejsze atuty
- 🎓 **Technologia logowania do konta eduVULCAN:** Bezpośrednie, bezpieczne logowanie do konta eduVULCAN (eduvulcan.pl). Automatyczny wybór profilu ucznia, import sprawdzianów, prac klasowych, kartkówek, zadań domowych oraz ocen i frekwencji.
- 📅 **Zintegrowany Terminarz:** Sprawdziany, kartkówki i zadania domowe są automatycznie parowane z konkretnymi godzinami lekcji w planie dnia, w siatce tygodnia oraz na widżetach.
- 🔕 **Auto DND (Wyciszanie w trakcie lekcji):** Automatyczne wyciszanie telefonu podczas trwania realnych zajęć i natychmiastowe przywracanie profilu dźwięku na przerwach w tle (`AlarmManager.setExactAndAllowWhileIdle`).
- ⏳ **Licznik i Odliczanie do wydarzeń:** Dynamiczne odliczanie do wakacji, ferii, egzaminów zawodowych lub 100-dniówki z wyliczaniem **dni szkolnych (pon–pt)**.
- 🛡️ **Strażnik Frekwencji (Attendance Guardian):** Matematyczny kalkulator wyliczający dopuszczalne nieobecności przed spadkiem poniżej ustawowego progu 50% oraz interaktywny symulator opuszczeń.
- ⚡ **Wbudowane aktualizacje 1 kliknięciem:** Sprawdzanie wydań w tle, pobieranie APK wewnątrz aplikacji i natychmiastowe uruchomienie instalatora systemowego bez odinstalowywania.

---

## Zrzuty ekranu

<p align="center">
  <img src="docs/screenshots/strona-glowna.png" alt="Strona główna" title="Strona główna" width="23%">
  <img src="docs/screenshots/plan.png" alt="Plan lekcji" title="Plan lekcji" width="23%">
  <img src="docs/screenshots/zastepstwa.png" alt="Zastępstwa" title="Zastępstwa" width="23%">
  <img src="docs/screenshots/odjazdy.png" alt="Odjazdy" title="Odjazdy" width="23%">
</p>
<p align="center">
  <img src="docs/screenshots/ogloszenia.png" alt="Ogłoszenia" title="Ogłoszenia" width="23%">
  <img src="docs/screenshots/ustawienia.png" alt="Ustawienia" title="Ustawienia" width="23%">
  <img src="docs/screenshots/widzety.png" alt="Widżety" title="Widżety" width="23%">
  <img src="docs/screenshots/powiadomienia.png" alt="Powiadomienia" title="Powiadomienia" width="23%">
</p>

---

## Główne Funkcje

### 🎓 Terminarz, oceny i konto eduVULCAN
- Obsługa bezpośredniego logowania do konta eduVULCAN (eduvulcan.pl) w bezpiecznym oknie logowania.
- Automatyczne wykrywanie konta i wybór profilu ucznia.
- Import sprawdzianów, prac klasowych, kartkówek, prac domowych oraz ocen cząstkowych.
- Opcjonalny mostek powiadomień z oficjalnej aplikacji eduVULCAN na telefonie.
- Obsługa zewnętrznych subskrypcji kalendarza iCal (`.ics` / `webcal://`).
- Zobacz przewodnik konfiguracji: [**PORADNIK_VULCAN.md**](PORADNIK_VULCAN.md).

### 🔕 Auto DND – Automatyczne wyciszanie na lekcjach
- Telefon automatycznie przełącza się w tryb cichy / Nie Przeszkadzać wyłącznie w trakcie Twoich lekcji.
- Natychmiastowe przywracanie profilu dźwięku (normalny, wibracje, cichy) na dzwonek oznaczający przerwę, w okienkach oraz po zakończeniu zajęć.
- Działa całkowicie w tle – nawet przy zablokowanym telefonie w kieszeni.

### ⏳ Odliczanie do wydarzeń (Countdown)
- Karta na ekranie głównym oraz konfiguracja w Ustawieniach.
- Predefiniowane szybkie presety: *Koniec roku szkolnego / Wakacje*, *Ferie zimowe*, *Egzamin zawodowy*, *100-dniówka*, *Rozpoczęcie roku*.
- Wybór dowolnej własnej daty z kalendarza Material 3 `DatePickerDialog` oraz własnego tytułu.
- Wyliczanie zarówno liczby wszystkich dni, jak i **dni szkolnych (poniedziałek–piątek)** z poprawną gramatyką języka polskiego.

### 🛡️ Strażnik Frekwencji (Attendance Guardian)
- Wyliczanie frekwencji ogółem oraz dla każdego przedmiotu z osobna.
- Algorytm obliczający, ile lekcji uczeń może jeszcze opuścić przed spadkiem poniżej 50% lub progu ostrzegawczego.
- Wskaźnik liczby kolejnych obecności potrzebnych do poprawy frekwencji.
- Interaktywny symulator opuszczeń (+1h, +2h, +4h, +8h) z podglądem na żywo.

### 🧹 Karta Dyżurnego Klasowego
- Informacja na ekranie głównym o nadchodzącym dyżurze (dzisiaj, w tym tygodniu, za X dni).
- Automatyczna rotacja par uczniów według numerka z dziennika lekcyjnego.
- Lista codziennych zadań dyżurnego (tablica, wietrzenie sali, pomoc nauczycielowi).

### ✏️ Własne Plany i Aktywności
- Możliwość wprowadzania własnych zadań (nauka, korepetycje, projekty, treningi) bezpośrednio do widoku dnia.
- Obsługa godzin od-do, notatek, oznaczania zadań jako ukończone oraz usuwania.

### 📅 Plan lekcji i Zastępstwa
- Widok pojedynczego dnia oraz pełnej siatki tygodnia zintegrowany z zastępstwami i etykietami sprawdzianów (`📝 Sprawdzian`, `📄 Kartkówka`, `📌 Wydarzenie`).
- Filtrowanie grup zajęciowych (języki, informatyka, WF).
- Natychmiastowy podgląd sal zastępczych i nauczycieli zastępujących.

### 🚍 Odjazdy ze szkoły (ZDMiKP Bydgoszcz)
- Rzeczywiste rozkłady jazdy i czas dojścia pieszego z ul. Karłowicza 20 na okoliczne przystanki (bez używania GPS).

### 📢 Ogłoszenia szkolne
- Wiadomości ze strony zse.bydgoszcz.pl z trybem offline i zapisywaniem do ulubionych.

### 📱 Widżety Glance na pulpit
- Widżety *Następna lekcja*, *Plan dnia* oraz *Zastępstwa* ze wskaźnikami nadchodzących sprawdzianów.

### ⚡ Wygodne aktualizacje
- Powiadomienie o nowej wersji i instalacja 1 kliknięciem bezpośrednio w aplikacji – nowa wersja instaluje się na starą bez utraty konfiguracji i danych.

---

## Bezpieczeństwo i Prywatność

Aplikacja została zaprojektowana z najwyższą dbałością o bezpieczeństwo danych i prywatność użytkownika:
1. **Bezpieczne logowanie eduVULCAN:** Logowanie do konta eduVULCAN odbywa się bezpośrednio w oficjalnym formularzu platformy (`eduvulcan.pl`) bez serwerów pośredniczących.
2. **Lokalne szyfrowanie danych:** Identyfikatory sesji, oceny oraz wydarzenia są zapisywane wyłącznie w chronionej przestrzeni aplikacji na Twoim urządzeniu.
3. **Brak wycieków do chmury:** Baza danych oraz pliki autoryzacji są wykluczone z mechanizmu Google Cloud Backup.
4. **Prywatność:** Brak trackerów, analityki, profilowania i reklam. Szczegóły: [PRYWATNOSC.md](PRYWATNOSC.md).

---

## Informacje dla programistów (Budowanie ze źródeł)

Projekt wykorzystuje nowoczesny stos technologiczny Androida:
* **Język:** Kotlin 2.0+ (Coroutines, Flow)
* **UI:** Jetpack Compose, Material 3
* **Architektura:** Clean Architecture + MVI/MVVM, Dagger Hilt
* **Baza Danych:** Room Database v5 (z atomowymi migracjami)
* **Zadania w tle:** WorkManager, Glance Widgets, AlarmManager
* **Sieć i Parsowanie:** OkHttp 4, Jsoup

Wymagania: JDK 17+ (zalecane JDK 21) oraz Android SDK (minSdk 26, targetSdk 35).

Kompilacja wariantu instalacyjnego FOSS:
```sh
./gradlew :app:assembleFossRelease
```

Uruchomienie testów jednostkowych:
```sh
./gradlew :app:testFossDebugUnitTest
```

---

## Licencja

Projekt udostępniany jest na warunkach otwartoźródłowej licencji [GNU General Public License v3.0 lub nowszej](LICENSE).
Inspiracją dla pierwotnego interfejsu był projekt Atom autorstwa Kacpra Górki.
