# Zgłaszanie podatności

Problemy z logowaniem, poświadczeniami, podpisem APK i instalatorem aktualizacji zgłaszaj
prywatnie przez [GitHub Security Advisories](https://github.com/Klap3K/Elektronv2/security/advisories/new).
Nie publikuj haseł, cookies, danych ucznia ani kluczy w publicznych issue lub PR-ach.
Jeżeli formularz zgłoszenia jest niedostępny, poproś właściciela repozytorium o włączenie
prywatnego zgłaszania podatności, bez ujawniania szczegółów błędu.

Poprawki bezpieczeństwa są przygotowywane dla bieżącej wersji aplikacji.

## Podpis wydań

Klucz znajdujący się wcześniej w `.github/signing/release.pfx.b64` został ujawniony wraz
z hasłem w historii repozytorium. Usunięcie bieżącego pliku nie unieważnia dostępnych kopii.
Ten podpis należy traktować jako skompromitowany.

Decyzja właściciela z 13.10.2026: przygotować instrukcję migracji, bez rotacji podpisu
i bez przepisywania historii w ramach tej poprawki. Dystrybucja pozostaje wyłącznie
na GitHub Releases, a zapamiętywanie hasła eduVULCAN służy nadal odnawianiu sesji,
z szyfrowaniem poświadczeń.

Workflow wymaga środowiska GitHub `release` oraz sekretów `RELEASE_KEYSTORE_BASE64`,
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` i `RELEASE_KEY_PASSWORD`.
Pierwszy sekret zawiera base64 gotowego magazynu JKS lub PKCS12, pozostałe jego dane podpisu.
Konfiguracja środowiska powinna wymagać akceptacji właściciela; reguły tagów `v*` powinny
ograniczać publikowanie do uprawnionych osób. Nie zapisuj sekretów w plikach workflow.

Właściciel musi ustalić plan rotacji: nowy klucz bez linii rotacji nie pozwala aktualizować
istniejących instalacji. Przed publikacją należy sprawdzić wsparcie rotacji na obsługiwanych
wersjach Androida oraz przetestować aktualizację istniejącego APK. Nie generuj nowego
podpisu bez takiego planu. Usunięcie klucza z historii wymaga kopii repozytorium, uzgodnienia
z współpracownikami i osobnej autoryzacji force-push; nie jest częścią zwykłej poprawki kodu.

### Procedura do wykonania przez właściciela

1. Wstrzymaj publikowanie, skonfiguruj środowisko `release`, wymagane zatwierdzanie wydań
   i reguły tagów `v*`. Wprowadź cztery wskazane wyżej sekrety w ustawieniach GitHuba.
   Użycie starego klucza zachowa zgodność instalacji, ale nie cofnie jego ujawnienia.
2. Przygotuj nowy klucz poza repozytorium i zachowaj jego zaszyfrowane kopie offline.
   Zdecyduj o ścieżce przejścia: zweryfikowana linia rotacji podpisu albo nowa instalacja.
   W drugim przypadku przygotuj instrukcję zachowania danych użytkownika przed odinstalowaniem.
3. Na urządzeniach z obsługiwanymi wersjami Androida sprawdź aktualizację APK obecnego wydania,
   zachowanie danych, podpis nowego APK oraz odrzucanie APK podpisanego nieuprawnionym kluczem.
   Publikuj dopiero po potwierdzeniu wyników i komunikacji do użytkowników.
4. Przed czyszczeniem historii zachowaj prywatną kopię repozytorium oraz oczyszczony, bieżący
   `release.yml` poza klonem roboczym. Uzgodnij przerwę z osobami pracującymi na repozytorium.
   W osobnym klonie lustrzanym można usunąć oba historyczne pliki poleceniem:

   ```sh
   git filter-repo --path .github/signing/release.pfx.b64 --path .github/workflows/release.yml --invert-paths
   ```

   Usunięcie obu ścieżek jest celowe: starsze wersje workflow zawierają jawne hasło.
   Polecenie usuwa także bieżący workflow, dlatego przywróć jego oczyszczoną wersję
   jako nowy commit i sprawdź ponownie całą historię. Nie uruchamiaj tego w głównym checkoutcie.
5. Dopiero po kontroli wszystkich zmienionych referencji wykonaj uzgodniony force-push.
   Współpracownicy powinni sklonować repo ponownie; stare klony mogą przywrócić ujawnione pliki.
   Historyczne kopie, forki i artefakty mogą nadal istnieć — czyszczenie historii nie zastępuje rotacji.

## Poświadczenia na urządzeniu

Poświadczenia konta eduVULCAN (ciasteczka sesyjne, tokeny) są szyfrowane AES-GCM kluczem Android Keystore.
Stare wartości DataStore są migrowane atomowo przed pierwszym odczytem. Baza danych Room, pliki sesji
oraz raporty awarii są wykluczone z systemowej kopii zapasowej w chmurze (Google Cloud Backup).
Logowanie i komunikacja API działają wyłącznie przez bezpośrednie połączenia HTTPS z domenami
VULCAN (`eduvulcan.pl`, `uczen.eduvulcan.pl`, `bcu.gov.pl`). Zgłoszenia nie mogą zawierać danych tych magazynów.
