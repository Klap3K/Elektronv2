# 📖 Poradnik: Jak połączyć Elektron-Vulcan z Dziennikiem VULCAN (API)

Ten poradnik wyjaśnia krok po kroku, jak uzyskać dane dostępu (**Token**, **Symbol**, **PIN**) ze szkolnego dziennika VULCAN / eduVULCAN i połączyć go z aplikacją **Elektron-Vulcan**, aby automatycznie pobierać sprawdziany, kartkówki i zadania domowe wprost do planu lekcji.

---

## 🔒 Bezpieczeństwo i prywatność

* **Nigdy nie podajesz hasła głównego** do swojego konta VULCAN.
* Połączenie odbywa się za pomocą oficjalnego protokołu rejestracji urządzeń mobilnych VULCAN (Hebe / UONET+).
* Aplikacja Elektron-Vulcan generuje na Twoim telefonie unikalną parę kluczy kryptograficznych RSA i certyfikat X.509.
* Twoje dane logowania i klucze są przechowywane **wyłącznie lokalnie w pamięci Twojego urządzenia** i służą wyłącznie do pobierania Twoich sprawdzianów i terminarza w trybie tylko do odczytu.

---

## 🚀 Krok po kroku: Skąd wziąć Token, Symbol i PIN?

### Krok 1: Zaloguj się do dziennika w przeglądarce
1. Otwórz przeglądarkę internetową (na komputerze lub w telefonie).
2. Wejdź na stronę swojego dziennika:
   * **Nowy system eduVULCAN:** [https://eduvulcan.pl](https://eduvulcan.pl)
   * **Tradycyjny UONET+:** adres Twojej szkoły, np. `https://uonetplus.vulcan.net.pl/bydgoszcz`
3. Zaloguj się na swoje konto ucznia (lub rodzica).

---

### Krok 2: Otwórz zakładkę „Dostęp mobilny”
1. Po zalogowaniu przejdź do widoku **Dziennik ucznia** (Uczeń).
2. W menu głównym (w zależności od wersji panelu):
   * **W nowym eduVULCAN:** Kliknij ikonę swojego profilu / koło zębate w prawym górnym rogu lub w menu bocznym wybierz **Dostęp mobilny** / **Aplikacja mobilna**.
   * **W klasycznym UONET+:** W menu po lewej stronie kliknij zakładkę **Dostęp mobilny** (lub u góry obok ikony kłódki / klucza).

---

### Krok 3: Wygeneruj kod rejestracyjny urządzenia
1. Kliknij przycisk **„Zarejestruj urządzenie”** (lub **„Nowe urządzenie”** / **„Wygeneruj kod dostępu”**).
2. Na ekranie wyświetli się **Kod QR**, a pod nim lub obok niego 3 kluczowe wartości:

| Pole | Przykład | Co to jest? |
| :--- | :--- | :--- |
| **Token** | `FK1000000000...` lub `C123456` | Unikalny kod sesji rejestracyjnej |
| **Symbol** | `bydgoszcz` | Identyfikator samorządu/miasta Twojej szkoły |
| **PIN** | `123456` | 6-cyfrowy kod numeryczny ważny jednorazowo |

> 💡 **Wskazówka:** Jeśli rejestrujesz się z telefonu, możesz także skopiować tekst zakodowany w kodzie QR (często jest to ciąg znaków w formacie JSON) i wkleić go w całości w aplikacji Elektron-Vulcan!

---

### Krok 4: Wpisz dane w aplikacji Elektron-Vulcan
1. Otwórz aplikację **Elektron-Vulcan** na telefonie.
2. Przejdź do zakładki **Ustawienia** (ikona koła zębatego).
3. Znajdź sekcję **„Terminarz i sprawdziany (VULCAN)”**.
4. Kliknij **„Połącz z VULCAN (Token, Symbol, PIN)”**.
5. Wpisz odczytane wartości:
   * **Token**
   * **Symbol** (domyślnie podpowiadana jest `bydgoszcz`)
   * **PIN**
   *(albo kliknij „Wklej kod QR / JSON” i wklej zawartość kodu)*
6. Kliknij **„Połącz”**.

Gotowe! Aplikacja autoryzuje urządzenie, pobierze terminarz i od tej pory wszystkie sprawdziany będą na bieżąco synchronizowane.

---

## 🎯 Gdzie zobaczysz swoje sprawdziany?

Po połączeniu sprawdziany automatycznie pojawią się w trzech miejscach:

1. **Na kafelkach lekcji w planie zajęć:**
   * Obok nazwy przedmiotu pojawi się etykieta, np. `SPRAWDZIAN` (czerwona) lub `KARTKÓWKA` (pomarańczowa).
2. **W szczegółach lekcji:**
   * Po tapnięciu w lekcję zobaczysz dokładny temat pracy klasowej, zakres materiału i nazwisko nauczyciela.
3. **Karta dnia w planie lekcji:**
   * Na samej górze widoku danego dnia pojawi się czytelna karta ze wszystkimi zaplanowanymi sprawdzianami.
4. **Ekran główny (Dashboard):**
   * Sekcja *„Nadchodzące sprawdziany”* pokaże Ci najważniejsze testy na najbliższe dni, abyś o niczym nie zapomniał.

---

## ❓ Najczęściej zadawane pytania (FAQ)

### 1. Wyświetla się błąd: „PIN wygasł” lub „Niepoprawny PIN”
Wygenerowany w przeglądarce PIN ma ograniczony czas ważności (zwykle od kilkunastu minut do 1-2 godzin). Jeśli minęło zbyt dużo czasu, po prostu kliknij w przeglądarce ponownie **„Zarejestruj urządzenie”** i wpisz świeży kod.

### 2. Jaki mam wpisać „Symbol”?
Symbol to fragment adresu internetowego Twojego dziennika szkoły:
* Dla szkół w Bydgoszczy (w tym ZSE Bydgoszcz) jest to: `bydgoszcz`.
* Jeśli Twoja szkoła używa innego adresu (np. `uonetplus.vulcan.net.pl/torun`), to Twoim symbolem jest `torun`.

### 3. Czy muszę logować się ponownie po restarcie aplikacji?
Nie. Po udanej rejestracji certyfikat urządzenia jest bezpiecznie zapisany w telefonie. Wystarczy wcisnąć przycisk **„Synchronizuj sprawdziany”**, a eLektron pobierze najnowsze wpisy.

### 4. Czy mogę w każdej chwili odłączyć konto?
Tak. W każdej chwili możesz wejść w Ustawienia → Terminarz i kliknąć **„Odłącz konto VULCAN”**. Spowoduje to natychmiastowe usunięcie kluczy i pobranych sprawdzianów z Twojego telefonu.
