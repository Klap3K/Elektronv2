<div align="center">

<img src="docs/logo.png" alt="Elektron-Vulcan" width="112">

# Elektron-Vulcan

**Plan lekcji, zastępstwa, ogłoszenia, odjazdy oraz pełna integracja z Dziennikiem VULCAN (Hebe API / terminarz) w nowoczesnej aplikacji na Androida dla uczniów ZSE w Bydgoszczy.**

[![Licencja: GPL v3+](https://img.shields.io/badge/licencja-GPL--3.0--or--later-blue)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
[![VULCAN API](https://img.shields.io/badge/VULCAN-Hebe%20API%20%2B%20iCal-blueviolet)](#funkcje)

[**Poradnik VULCAN (Jak podłączyć konto)**](PORADNIK_VULCAN.md) • [**Raport Audytu i Poprawek**](AUDYT_PROJEKTU.md)

</div>

## O projekcie Elektron-Vulcan

**Elektron-Vulcan** to rozwinięcie i rozbudowana wersja aplikacji dla uczniów Zespołu Szkół Elektronicznych w Bydgoszczy. Aplikacja zbiera w jednym miejscu wszystkie kluczowe informacje szkolne: plan Twojej klasy i grup, zastępstwa, ogłoszenia ze strony szkoły, połączenia komunikacji miejskiej, a także **pełny terminarz sprawdzianów, kartkówek i prac domowych importowany bezpośrednio z Dziennika VULCAN**.

### Geneza i inspiracja
* Projekt jest bezpośrednio inspirowany projektem **[Atom](https://github.com/kacpergorka/atom)** autorstwa **[Kacpra Górki](https://github.com/kacpergorka)** z GitHuba (stworzonym na systemy Apple iOS/macOS).
* Baza kodu Androida wywodzi się z otwartoźródłowego projektu [eLektron](https://github.com/stasolejnik/elektron) Stanisława Olejnika.
* **Elektron-Vulcan** wzbogaca ten ekosystem o:
  - 🎓 **Oficjalne API mobilne VULCAN (Hebe API):** Bezpieczne logowanie urządzeniem mobilnym (Token, Symbol, PIN / kod QR), generowanie kryptograficznych kluczy RSA-2048 i certyfikatów X.509 DER, podpisywanie zapytań HTTP nagłówkiem `Signature`.
  - 📅 **Inteligentny Terminarz:** Automatyczne parowanie sprawdzianów i kartkówek z konkretnymi lekcjami w planie dnia oraz w siatce tygodnia.
  - 🔄 **Cykliczna synchronizacja w tle:** Odświeżanie danych co 15 minut (WorkManager + SyncCoordinator) oraz przy geście *Pull-to-refresh*.
  - 🛡️ **Kompleksowy audyt bezpieczeństwa:** Brak przechowywania zbędnych haseł/PIN-ów, wykluczenie kluczy prywatnych z chmury Google Cloud Backup, transakcyjna atomowość bazy Room oraz odporność na przesunięcia stref czasowych UTC.

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

- **Terminarz i Dziennik VULCAN:**
  - Import sprawdzianów, kartkówek, testów i prac domowych bezpośrednio z eduVULCAN / UONET+.
  - Logowanie za pomocą jednorazowego kodu (Token, Symbol, PIN) lub wklejenia zawartości kodu QR.
  - Opcjonalne wsparcie dla zewnętrznych subskrypcji kalendarza iCal (`.ics` / `webcal://`).
  - Sprawdź szczegółowy przewodnik: [**PORADNIK_VULCAN.md**](PORADNIK_VULCAN.md).
- **Plan lekcji:**
  - Widok pojedynczego dnia i pełnej siatki tygodnia zintegrowany z zastępstwami oraz odznakami sprawdzianów (`📝`, `📄`, `📌`).
  - Filtrowanie grup zajęciowych (języki obce, WF, przedmioty zawodowe).
- **Zastępstwa:**
  - Natychmiastowy podgląd zmian dotyczących Twojej klasy i grup, z uwzględnieniem sal i nauczycieli zastępujących.
- **Notatki i Przypomnienia:**
  - Możliwość dodawania własnych notatek do lekcji z powiadomieniem przed zajęciami lub dzień wcześniej.
- **Odjazdy ze szkoły (ZDMiKP Bydgoszcz):**
  - Rozkłady jazdy i czas dojścia pieszego z ul. Karłowicza 20 na pobliskie przystanki (bez użycia GPS).
- **Ogłoszenia szkolne:**
  - Archiwum wiadomości ze strony zse.bydgoszcz.pl z trybem offline i ulubionymi.
- **Widżety i Szybki Dostęp:**
  - Widżety Glance na ekran główny: *Następna lekcja*, *Plan dnia*, *Zastępstwa*.
- **Personalizacja:**
  - Dynamiczne kolory Material You (Android 12+), ciemny motyw, własne kolory i skróty przedmiotów.

---

## Bezpieczeństwo i Prywatność

Projekt został poddany rygorystycznemu audytowi technicznemu (zobacz [**AUDYT_PROJEKTU.md**](AUDYT_PROJEKTU.md)):
1. **Brak loginu i hasła głównego:** Rejestracja w VULCAN opiera się na mechanizmie urządzeń mobilnych – aplikacja nie pyta i nie przechowuje Twojego hasła do konta Vulcan.
2. **Klucz prywatny RSA na urządzeniu:** Klucz kryptograficzny generowany jest lokalnie na telefonie i nigdy nie jest wysyłany na zewnętrzne serwery.
3. **Brak wycieków do Google Drive:** Baza danych oraz pliki poświadczeń są wykluczone z mechanizmu Google Cloud Backup.
4. **Prywatność:** Aplikacja nie posiada modułów śledzących, analityki ani reklam. Szczegóły: [PRYWATNOSC.md](PRYWATNOSC.md).

---

## Dla Twórców i Rozwijających (Kompilacja)

Projekt wykorzystuje nowoczesny stos technologiczny Androida:
* **Język:** Kotlin 2.0+ (korutyny, Flow)
* **UI:** Jetpack Compose, Material 3
* **Architektura:** Clean Architecture + MVI/MVVM, Dagger Hilt
* **Baza Danych:** Room Database v4 (z atomowymi migracjami)
* **Zadania w tle:** WorkManager, Glance Widgets
* **Sieć i Krypto:** OkHttp, Jsoup, Java Cryptography Architecture (RSA 2048, X.509 DER)

Wymagania: JDK 17+ oraz Android SDK (minSdk 26, targetSdk 35).

Kompilacja wariantu FOSS:
```sh
./gradlew :app:assembleFossDebug
```

Uruchomienie testów jednostkowych:
```sh
./gradlew :app:testFossDebugUnitTest
```

---

## Licencja

Projekt objęty jest licencją [GNU General Public License v3.0 or later](LICENSE).
Podziękowania dla Kacpra Górki (autor projektu [Atom](https://github.com/kacpergorka/atom)) za inspirację interfejsem oraz dla Stanisława Olejnika za pierwotną bazę projektu [eLektron](https://github.com/stasolejnik/elektron).
