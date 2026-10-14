# 📖 Poradnik: Jak połączyć Elektron-Vulcan z kontem eduVULCAN

Ten poradnik wyjaśnia krok po kroku, jak połączyć konto **eduVULCAN** z aplikacją **Elektron-Vulcan**, aby automatycznie pobierać sprawdziany, prace klasowe, kartkówki, zadania domowe oraz oceny i frekwencję wprost do planu lekcji.

---

## 💡 Dlaczego logowanie do konta eduVULCAN?

Tradycyjne mobilne API VULCAN (oparte na kodach rejestracyjnych: Token, Symbol, PIN) w większości szkół przestało działać po przejściu na scentralizowany system **eduVULCAN**. 

Dlatego **Elektron-Vulcan** obsługuje nowoczesną, bezpośrednią technologię logowania do konta **eduVULCAN** (`eduvulcan.pl`), zapewniając stabilną i natychmiastową synchronizację danych.

---

## 🔒 Bezpieczeństwo i prywatność

* **Brak serwerów pośredniczących:** Logowanie odbywa się bezpośrednio na oficjalnych stronach eduVULCAN (`eduvulcan.pl`). Twoje dane logowania ani hasło nigdy nie trafiają do twórców aplikacji ani na zewnętrzne serwery.
* **Bezpieczny magazyn Androida:** Dane autoryzacyjne (ciasteczka sesyjne) są szyfrowane i zapisywane wyłącznie lokalnie w chronionym magazynie Twojego telefonu.
* **Pełna kontrola:** W każdej chwili możesz wylogować się i odłączyć konto w Ustawieniach, co natychmiast usuwa wszystkie zaimportowane dane.

---

## 🚀 Metoda 1: Bezpośrednie logowanie do eduVULCAN (Zalecane)

To najwygodniejszy i w pełni zautomatyzowany sposób:

1. Otwórz aplikację **Elektron-Vulcan** na telefonie.
2. Przejdź do zakładki **Ustawienia** (ikona koła zębatego w dolnym pasku nawigacji).
3. Znajdź sekcję **„Dziennik eduVULCAN (Oceny, Sprawdziany, Frekwencja)”**.
4. Kliknij **„Zaloguj się do eduVULCAN (Login i Hasło)”**.
5. W wyświetlonym oknie logowania:
   - Wpisz swój login (adres e-mail) oraz hasło do konta eduVULCAN.
   - Kliknij **„Zaloguj się”**.
6. Aplikacja automatycznie przeprowadzi autoryzację, wykryje Twój profil ucznia w szkole (np. *Igor Szwarc (ZSE)*) i pobierze aktualne dane.
7. Gotowe! Po zalogowaniu sprawdziany, kartkówki, prace domowe, oceny i frekwencja zostaną natychmiast zsynchronizowane.

---

## 📲 Metoda 2: Zaciąganie danych z aplikacji eduVULCAN na telefonie

Jeśli masz już na swoim telefonie zainstalowaną oficjalną aplikację mobilną eduVULCAN i jesteś w niej zalogowany, możesz skorzystać z wbudowanego mostka powiadomień:

1. W **Ustawieniach** aplikacji Elektron-Vulcan odszukaj sekcję **„Dziennik eduVULCAN”**.
2. Kliknij opcję **„Zaciągaj dane z aplikacji eduVULCAN na telefonie”**.
3. Przyznaj uprawnienie do odczytu powiadomień w ustawieniach systemu Android.
4. Gdy oficjalny dziennik eduVULCAN przyśle powiadomienie o nowym sprawdzianie, kartkówce lub zadaniu domowym, Elektron-Vulcan automatycznie przechwyci wpis i doda go do Twojego terminarza!

---

## 🗓️ Metoda 3: Alternatywny link kalendarza iCal (.ics)

Dla użytkowników korzystających z zewnętrznych kalendarzy:
1. W sekcji **Dziennik eduVULCAN** kliknij **„Alternatywny link kalendarza iCal”**.
2. Wklej publiczny lub prywatny link do kalendarza w formacie `.ics` (lub `webcal://`).

---

## 🎯 Gdzie zobaczysz swoje sprawdziany i oceny?

Po połączeniu dane pojawią się automatycznie w kluczowych miejscach aplikacji:

1. **Na kafelkach lekcji w planie zajęć:**
   - Obok nazwy przedmiotu pojawią się kolorowe etykiety: `📝 SPRAWDZIAN`, `📄 KARTKÓWKA`, `📌 ZADANIE`.
2. **W szczegółach lekcji:**
   - Po dotknięciu danej lekcji zobaczysz dokładny opis pracy klasowej, zakres materiału oraz nazwisko nauczyciela.
3. **Ekran główny (Dashboard):**
   - Karta *Nadchodzące sprawdziany* z odliczaniem dni do najbliższych testów.
4. **Widżety Glance na pulpicie:**
   - Widżety *Plan dnia* i *Następna lekcja* bezpośrednio pokazują oznaczenia sprawdzianów na ekranie głównym Twojego telefonu.
5. **Strażnik Frekwencji, Symulator Ocen i Bilans Godzin:**
   - Wirtualny symulator ocen („Co jeśli”) z natychmiastowym przeliczaniem średniej ważonej przedmiotu oraz średniej ogólnej.
   - Dokładny podgląd obecności z konfigurowalnym progiem docelowym (50% – 95%), wyliczeniem bezpiecznego zapasu godzin oraz godzin do odrobienia.

---

## ❓ Najczęściej zadawane pytania (FAQ)

### 1. Dlaczego nie ma już logowania Tokenem, Symbolem i PIN-em?
Dziennik VULCAN po modernizacji do eduVULCAN wycofał powszechną obsługę starszego API mobilnego dla wielu szkół i kont. Bezpośrednie logowanie do konta eduVULCAN jest oficjalnym, niezawodnym i w 100% działającym rozwiązaniem.

### 2. Czy moje hasło jest bezpieczne?
Tak. Formularz logowania komunikuje się wyłącznie z oficjalnymi serwerami VULCAN (`eduvulcan.pl`). Hasło nie jest nigdzie zapisywane ani przesyłane do dewelopera – aplikacja przechowuje jedynie bezpieczny token sesji w chronionej przestrzeni aplikacji na telefonie.

### 3. Co zrobić, gdy mam konto z kilkoma profilami lub rodzeństwem?
Elektron-Vulcan automatycznie wykrywa profile powiązane z Twoim kontem i pozwala wybrać właściwego ucznia lub zmienić profil w każdej chwili w Ustawieniach.

### 4. Jak odświeżyć dane?
Wystarczy pociągnąć ekran główny w dół (Pull-to-Refresh) lub wejść w Ustawienia → Dziennik eduVULCAN i kliknąć **„Synchronizuj oceny, sprawdziany i frekwencję”**.
