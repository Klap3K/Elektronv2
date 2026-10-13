# Polityka prywatności - Elektron-Vulcan

Ostatnia aktualizacja: październik 2026

Elektron-Vulcan to nieoficjalna aplikacja z planem lekcji, zastępstwami, terminarzem VULCAN i ogłoszeniami
Zespołu Szkół Elektronicznych w Bydgoszczy. Poniżej opisujemy, jakie dane przetwarza.

## W skrócie

- Nie zakładasz konta i nie podajesz żadnych danych osobowych.
- Aplikacja nie zawiera reklam, analityki ani śledzenia.
- Twoje ustawienia są zapisane tylko na Twoim telefonie.

## Dane zapisane na telefonie

- **Ustawienia:** wybrana klasa, grupy zajęciowe, motyw, ustawienia powiadomień, układ dolnego
  paska, ekran startowy oraz ustawienia Odjazdów (przystanki i widoczność karty na stronie głównej).
- **Kopia danych ze strony szkoły:** plan lekcji, zastępstwa i ogłoszenia (żeby aplikacja
  działała bez internetu).
- **Ulubione ogłoszenia:** wybrane wpisy i pobrana treść artykułów, przechowywane lokalnie również po czyszczeniu danych podręcznych.
- **Raport ostatniej awarii** (tylko jeśli aplikacja się zamknęła z powodu błędu): wersja
  aplikacji, model telefonu, wersja Androida, techniczny opis błędu, stan ustawień ważnych dla
  działania aplikacji (czy powiadomienia są włączone, oszczędzanie baterii, dokładne alarmy,
  rodzaj połączenia z internetem) i ostatnie logi samej aplikacji (komunikaty techniczne, np.
  o błędach połączenia ze stroną szkoły - bez danych osobowych). Przy następnym uruchomieniu możesz go skopiować, wysłać e-mailem do dewelopera,
  zgłosić na GitHubie albo pominąć - w każdym przypadku raport jest potem usuwany.
  Aplikacja nigdy nie wysyła go sama. Taki sam raport (bez awarii) tworzy przycisk
  **Zgłoś problem** w Ustawieniach.

Aplikacja nie wysyła tych danych automatycznie. Przyciski **Udostępnij dzień** i **Udostępnij tydzień** pozwalają przekazać plan wybranego dnia lub tygodnia (klasę, godziny, przedmioty, sale, nauczycieli i zastępstwa) do aplikacji, którą sam wybierzesz w systemowym oknie udostępniania.

Ustawienia oraz wybrane ulubione ogłoszenia wraz z pobraną treścią mogą trafić do kopii zapasowej Androida
(Google), jeśli masz ją włączoną w telefonie - zwykła baza podręczna danych ze strony szkoły jest z niej
wykluczona. Odinstalowanie aplikacji usuwa wszystkie te dane.

## Połączenia z internetem

- **Strony szkoły** (plan.zse.bydgoszcz.pl, zastepstwa.zse.bydgoszcz.pl,
  zse.bydgoszcz.pl i zse.edu.bydgoszcz.pl): aplikacja pobiera z nich publicznie dostępne
  informacje. Po otwarciu ogłoszenia lub dodaniu go do ulubionych pobiera także treść artykułu do czytania offline. Serwery szkoły widzą, jak przy każdej stronie internetowej, adres IP
  i wersję aplikacji.
- **Firebase Cloud Messaging (Google)** - tylko w wersji z GitHuba (w wersji z F-Droid
  Firebase nie ma): służy wyłącznie do powiadomień push o nowych zastępstwach
  i ogłoszeniach. Telefon subskrybuje ogólne tematy powiadomień wspólne dla wszystkich
  użytkowników - nie jest przesyłana wybrana klasa ani żadne dane osobowe. Google przetwarza
  przy tym techniczny identyfikator instalacji aplikacji, zgodnie
  z [zasadami prywatności Google](https://policies.google.com/privacy).
  O tym, które powiadomienia pokazać, decyduje aplikacja na telefonie, na podstawie
  Twoich ustawień.
- **GitHub (sprawdzanie aktualizacji)** - tylko w wersji z GitHuba: najwyżej co kilka
  godzin aplikacja pobiera z api.github.com publiczną listę wydań eLektrona, żeby sprawdzić,
  czy jest nowsza wersja (domyślnie tylko wydania stabilne; kanał „Beta” w Ustawieniach obejmuje
  też wersje testowe). Po naciśnięciu **Aktualizuj** pobiera z GitHuba plik APK nowej
  wersji (zapisywany tymczasowo w pamięci podręcznej aplikacji) i otwiera instalator systemu -
  instalację zawsze zatwierdzasz sam. GitHub widzi przy tym adres IP i wersję aplikacji; nie są
  wysyłane żadne inne dane. Zasady GitHuba: https://docs.github.com/site-policy/privacy-policies

- **Zdjęcia w ogłoszeniach:** po otwarciu ogłoszenia pobierane są zdjęcia umieszczone w jego treści, wyłącznie z serwerów szkoły (obrazy z innych stron nie są pobierane - zostaje opis i link). Zdjęcia korzystają z pamięci podręcznej. Pełny tekst jest pobierany ze strony ogłoszenia przy pierwszym otwarciu (i ponownie, gdy zapisała go starsza wersja aplikacji).

- **BUSearch (odjazdy ze szkoły):** po otwarciu wyboru przystanku aplikacja
  pobiera publiczny katalog z api.busearch.pl. Katalog jest zapisywany w pamięci podręcznej;
  wyszukiwanie nazw i wybór celu odbywają się lokalnie. Przy samym wyborze celu nie są
  przesyłane wpisywane frazy, wybrany przystanek ani lokalizacja telefonu. Serwer widzi adres IP i wersję aplikacji.
  Po otwarciu zakładki Odjazdy, ręcznym odświeżeniu lub oglądaniu kafelka odjazdu na
  stronie głównej wysyła do planera BUSearch identyfikatory stanowisk przystanków
  w promieniu 1 km od szkoły albo stanowisk wybranego przystanku początkowego,
  współrzędne przystanku docelowego i czas rozpoczęcia podróży.
  Dzięki temu pobiera połączenia i godziny odjazdów. Cel podróży jest więc przekazywany
  dostawcy tylko przy wyszukiwaniu połączeń. Preferowany przystanek początkowy jest wtedy
  również przekazywany planerowi. Historia pięciu ostatnich celów i pięciu ostatnich
  przystanków początkowych jest zapisywana wyłącznie lokalnie wraz z ustawieniami; może
  wejść do systemowej kopii zapasowej Androida. Ostatni wynik wyszukiwania połączeń (linie,
  godziny i przystanki) jest zapisywany w pamięci podręcznej aplikacji, poza kopią zapasową,
  żeby po ponownym otwarciu był widoczny od razu; nowe sprawdzenie odbywa się najwyżej co
  10 minut, gdy karta lub zakładka jest na ekranie. Funkcja nie korzysta z GPS ani cyklicznych
  połączeń w tle.
- **Trasy piesze (FOSSGIS / OpenStreetMap):** przy sprawdzaniu odjazdów aplikacja może
  wysłać do routing.openstreetmap.de stały punkt szkoły i współrzędne przystanków początkowych,
  aby obliczyć dojście ulicami i ścieżkami. Serwer widzi IP i wersję aplikacji; nie otrzymuje
  GPS telefonu, celu podróży, klasy ani notatek. Wyniki są zapisywane lokalnie na 7 dni.
  Zasady: https://routing.openstreetmap.de/about.html
- **OpenStreetMap:** wybranie widoku mapy pobiera jej fragmenty z tile.openstreetmap.org.
  Serwer widzi adres IP, wersję aplikacji i oglądany obszar mapy. GPS, klasa, notatki i
  nazwa wybranego celu nie są do niego wysyłane. Fragmenty mapy są przechowywane lokalnie
  w pamięci podręcznej. Zasady prywatności: https://osmfoundation.org/wiki/Privacy_Policy

**Wersja z F-Droid** łączy się ze stronami szkoły i, po otwarciu wyboru przystanku,
również z katalogiem i planerem BUSearch oraz, po otwarciu mapy, z OpenStreetMap oraz z planerem tras pieszych FOSSGIS. Nie zawiera Firebase ani sprawdzania aktualizacji z GitHuba.

## Notatki do lekcji

Notatki i ustawienie ich przypomnień są zapisywane lokalnie na telefonie, oddzielnie od danych podręcznych szkoły. Nie są wysyłane do szkoły, Firebase ani GitHuba; mogą wejść do systemowej kopii zapasowej Androida, tak jak ustawienia. Czyszczenie danych podręcznych ich nie usuwa. Przypomnienia działają lokalnie i są domyślnie wyłączone. Treść notatki jest ukrywana na ekranie blokady zgodnie z ustawieniami systemu.

Udostępnianie zastępstw przekazuje wybrane informacje do aplikacji wskazanej przez użytkownika w systemowym oknie udostępniania. Własne notatki nie są dodawane do udostępnianego planu ani zastępstw.

## Kalendarz i terminarz (VULCAN API / iCal)

Połączenie z Dziennikiem VULCAN za pomocą API mobilnego (Token, Symbol, PIN) lub linku iCal (.ics) jest w pełni opcjonalne.
Aplikacja nie prosi o hasło główne do konta. Przy połączeniu przez API generowana jest unikalna para kluczy kryptograficznych RSA i certyfikat urządzenia,
a zapytania wysyłane są bezpośrednio do oficjalnych serwerów VULCAN. Pobrane wydarzenia (sprawdziany, kartkówki, zadania domowe) oraz klucze uwierzytelniające
są zapisywane wyłącznie w lokalnej bazie i pamięci DataStore na Twoim telefonie – nie są wysyłane do żadnych zewnętrznych serwisów, serwerów pośredniczących ani dewelopera.
W każdej chwili możesz odłączyć konto VULCAN i usunąć wszystkie zaimportowane wydarzenia w Ustawieniach aplikacji.

## Uprawnienia

- **Internet i stan sieci:** pobieranie danych ze stron szkoły, powiadomień push i odjazdów;
  synchronizacja w tle czeka na połączenie.
- **Powiadomienia:** informowanie o nowych zastępstwach i ogłoszeniach oraz przypomnienia
  (możesz je wyłączyć w ustawieniach aplikacji albo telefonu).
- **Alarmy o dokładnej godzinie:** punktualne przypomnienia o lekcjach i notatkach.
- **Uruchamianie po starcie telefonu:** przywrócenie przypomnień i synchronizacji po ponownym
  uruchomieniu.
- **Instalowanie aplikacji** (tylko wersja z GitHuba): otwarcie instalatora pobranej aktualizacji.
  Instalację zawsze zatwierdzasz sam.

## Dzieci

Aplikacja jest przeznaczona dla uczniów, także niepełnoletnich. Nie wymaga konta, nie prosi
o imię, nazwisko, adres e-mail ani numer telefonu i nie tworzy profili użytkowników.

## Kod źródłowy i kontakt

Elektron-Vulcan jest wolnym oprogramowaniem – kod źródłowy jest publicznie dostępny:
https://github.com/Klap3K/Elektronv2

Zgłoszenia błędów i kontakt: https://github.com/Klap3K/Elektronv2/issues
