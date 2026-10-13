package pl.zse.bydgoszcz.elektron.presentation.common

object Changelog {

    data class Entry(val versionCode: Int, val versionName: String, val items: List<String>)

    val entries: List<Entry> = listOf(
        Entry(43, "2.0.6", listOf(
            "Odliczanie do wydarzeń (Wakacje, 100-dniówka, Egzaminy) z licznikiem dni szkolnych i Material 3 kalendarzem",
            "Auto DND: automatyczne wyciszanie na lekcjach i przywracanie profilu dźwięku na przerwach w tle",
            "Wbudowane pobieranie aktualizacji i instalator jednym kliknięciem bez wychodzenia do przeglądarki"
        )),
        Entry(38, "2.0.1", listOf(
            "Osobny identyfikator pakietu (pl.zse.bydgoszcz.elektron.vulcan) — instalacja obok oryginalnej aplikacji",
            "Zmiana nazwy aplikacji na Elektron-Vulcan oraz nowa ikona aplikacji i logo"
        )),
        Entry(37, "2.0.0", listOf(
            "Elektron-Vulcan: Obsługa technologii logowania do konta eduVULCAN, terminarz sprawdzianów i oceny",
            "Strażnik Frekwencji: obliczanie procentu obecności, limitu opuszczeń przed 50% i symulator",
            "Dyżurny klasowy: karta dyżuru na pulpicie i automatyczna rotacja tygodniowa",
            "Własne plany i aktywności użytkownika w terminarzu i planie lekcji (Room v5)",
            "Sprawdziany i kartkówki widoczne w widżecie Plan dnia i Następna lekcja na pulpicie telefonu"
        )),
        Entry(36, "1.0.1-beta1", listOf(
            "Zmiana samych uwag w zastępstwie (np. „za ostatnią lekcję”) aktualizuje powiadomienie",
            "Wczytanie starszych ogłoszeń nie wywołuje już powiadomień o dawnych wpisach",
            "Odświeżenie planu na przyszłym tygodniu aktualizuje też bieżący tydzień, widżety i przypomnienia",
            "Spóźnione przypomnienie o lekcji nie znika po synchronizacji w tle",
            "Widżet Plan dnia: dotknij „za Tobą”, aby zobaczyć wcześniejsze lekcje",
            "Odjazdy: kolejne połączenia pojawiają się płynnie po „Pokaż więcej”",
            "Strona główna: przy zastępstwie najpierw informacja od szkoły (np. „Zajęcia w auli”), pod nią nauczyciel",
            "Plan tygodniowy pokazuje treść zastępstwa zamiast samej strzałki",
            "Powiadomienia o zastępstwach działają także, gdy części wpisów na stronie szkoły nie da się odczytać",
            "Widżety pomijają lekcje, z których klasa jest zwolniona",
            "Dwa zastępstwa tej samej lekcji nie zastępują się w powiadomieniach",
            "Strona główna i plan nie pokazują jako trwającej lekcji, z której klasa jest zwolniona",
            "Plan tygodniowy: z pustego tygodnia można przejść gestem, a w weekend „dziś” to nadchodzący tydzień",
            "Kanał aktualizacji Stabilne zawsze widzi najnowszą wersję stabilną"
        )),
        Entry(35, "1.0.0", listOf(
            "Pierwsza stabilna wersja eLektrona",
            "Zastępstwa za lekcje, na które nie chodzisz, nie trafiają na stronę główną ani do powiadomień",
            "Czytelniejsze powiadomienia o zastępstwach: dzień i lekcja w tytule, „Za: przedmiot · nauczyciel”",
            "Poprawione przez szkołę zastępstwo zastępuje poprzednie powiadomienie",
            "Widżety: Zastępstwa pokazują czas sprawdzenia, Plan dnia zaczyna od bieżącej lekcji, a przezroczyste dopasowują tekst do tapety",
            "Przedmiot przy zastępstwach w zakładce i na stronie głównej",
            "Nowy widok ogłoszeń: cała treść, a zdjęcia w tym miejscu, co w poście",
            "Uporządkowane Ustawienia i wybór kanału aktualizacji: stabilne albo beta",
            "Wybór ikony aplikacji i krótsze skróty po przytrzymaniu ikony, także do Odjazdów",
            "Szybsze Odjazdy: ostatni wynik od razu po uruchomieniu, pierwsze kursy przed sprawdzeniem wszystkich przystanków i mniej zapytań",
            "Mniejsze zużycie baterii: widżety i nocna synchronizacja rzadziej budzą telefon"
        )),
        Entry(34, "1.0.0-rc7", listOf(
            "Usprawnienia Odjazdów i wyboru przystanków",
            "Czytelniejsze godziny i wyniki połączeń",
            "Równy pasek nawigacji ze zmianą kolejności ikon",
            "Pewniejszy zapis notatek, grup i ulubionych",
            "Poprawki odświeżania danych, przypomnień i widżetów"
        )),
        Entry(33, "1.0.0-rc6", listOf(
            "Nowa funkcja Odjazdy ze szkoły, włączana w Ustawieniach po wyborze celu",
            "Wyszukiwanie przystanków i mapa OpenStreetMap bez GPS",
            "Linia, kierunek, godziny rozkładowe, przyjazd i czas dojścia pieszo",
            "Wybór preferowanego przystanku początkowego oraz zmiana celu w Odjazdach",
            "Połączenia bezpośrednie lub opcjonalnie z przesiadką",
            "Pięć ostatnich celów i przystanków początkowych; Pokaż więcej dla późniejszych odjazdów"
        )),
        Entry(
            versionCode = 25,
            versionName = "1.0.0-rc5.1",
            items = listOf(
                "Edytor notatek nie otwiera się ponownie podczas pisania i zapisywania",
                "Notatki w widżetach Następna lekcja, Plan dnia i Zastępstwa",
                "Długość podglądu zależy od miejsca i rozmiaru czcionki; pełna treść po dotknięciu lekcji",
                "Widżety odświeżają notatki po zapisaniu lub usunięciu",
                "Poprawne wykrywanie aktualizacji hotfix RC5.1"
            )
        ),
        Entry(
            versionCode = 24,
            versionName = "1.0.0-rc5",
            items = listOf(
                "Udostępnianie planu dnia i tygodnia z grupami i zastępstwami",
                "Notatki do przyszłych lekcji po przytrzymaniu; edycja, usuwanie i lista w Ustawieniach",
                "Przypomnienia o notatkach: wybrana godzina dzień wcześniej lub liczba minut przed lekcją",
                "Udostępnianie jednego zastępstwa po przytrzymaniu oraz wszystkich widocznych z paska",
                "Skróty ikony: Strona główna, Plan lekcji, Zastępstwa i Ogłoszenia",
                "Ulubione offline, liczba zakładek i usuwanie wszystkich z potwierdzeniem",
                "Ogłoszenia zachowują formatowanie bez nadmiernych odstępów",
                "Zwarty pasek planu, prostszy status przypomnień i poprawki dużej czcionki",
                "Poprawki przełączania list, przypomnień i zachowania danych przy synchronizacji"
            )
        ),
        Entry(
            versionCode = 23,
            versionName = "1.0.0-rc4",
            items = listOf(
                "Mniejsze zużycie transferu dzięki poprawionej pamięci podręcznej sieci",
                "Rzadsze pobieranie planu, listy klas i ogłoszeń w tle; zastępstwa nadal co 15 minut",
                "Przypomnienia uwzględniają zastępstwa otrzymane przez push",
                "Szybsze przerywanie pobierania przy zmianie klasy",
                "Mniej zapisów identycznych danych i odświeżeń widżetów",
                "Poprawki pamięci planu, dopasowania tekstu i zegarów niewidocznych zakładek",
                "Pewniejsze wykrywanie błędnych stron szkoły i pobieranie starszych ogłoszeń"
            )
        ),
        Entry(
            versionCode = 22,
            versionName = "1.0.0-rc3",
            items = listOf(
                "Dotknięcie zastępstwa (zakładka i widżet) otwiera szczegóły tej lekcji w planie",
                "Widżety Następna lekcja i Plan dnia też otwierają szczegóły lekcji",
                "Szczegóły lekcji otwierają się od razu w całości",
                "Widżet Zastępstwa pokazuje tyle zastępstw, ile się mieści, a resztę jako „+N więcej”",
                "Jedna wspólna synchronizacja: bez podwójnych pobrań i powiadomień",
                "Poprawki stabilności: nieczytelne godziny planu, kodowanie znaków, aktualizacja w aplikacji, widżety"
            )
        ),
        Entry(
            versionCode = 21,
            versionName = "1.0.0-rc2",
            items = listOf(
                "Zastępstwa dla grup: każda grupa widzi swoje zastępstwo (plan, widżety, przypomnienia)",
                "Nierozpoznany wpis na stronie zastępstw nie kasuje już poprawnych zastępstw z tego dnia",
                "Aktualizacja w aplikacji działa też na wolnym łączu",
                "Gdy strona szkoły nie działa, aplikacja to pokazuje zamiast udawać aktualne dane",
                "Przypomnienia i widżety odświeżają się po zmianie klasy i po ręcznym odświeżeniu",
                "Plan minionych tygodni nie jest nadpisywany bieżącym planem"
            )
        ),
        Entry(
            versionCode = 20,
            versionName = "1.0.0-rc1",
            items = listOf(
                "Nowy widok tygodnia: siatka z godzinami i pięcioma dniami, szczegóły lekcji po dotknięciu",
                "Raport błędu z logami: kopiowanie albo zgłoszenie na GitHubie",
                "Zgłaszanie uwag i problemów (Ustawienia → O aplikacji)",
                "Sprawdzanie aktualizacji w osobnej sekcji Ustawień",
                "Gdy nie uda się odświeżyć planu albo zastępstw, aplikacja to pokazuje",
                "Czas do końca lekcji i przerwy aktualny od razu po powrocie do aplikacji",
                "Poprawki stabilności i pobierania danych ze strony szkoły"
            )
        ),
        Entry(
            versionCode = 19,
            versionName = "0.6.6-beta",
            items = listOf(
                "Dzisiejsze zastępstwa zostają w planie, gdy szkoła opublikuje już zastępstwa na jutro",
                "Odwołanie wszystkich zastępstw danego dnia jest od razu widoczne",
                "Nowy plan lekcji ogłoszony z wyprzedzeniem obowiązuje dopiero od swojej daty",
                "Gdy strona szkoły nie odpowiada, aplikacja to pokazuje, zamiast udawać aktualne dane",
                "Zrozumiałe komunikaty błędów zamiast technicznych",
                "Przypomnienia o lekcjach od razu po aktualizacji aplikacji i restarcie telefonu",
                "Kolory akcentu dostępne także przy kolorach z tapety - wybór koloru je wyłącza",
                "Widżet Plan dnia: ile zostało do końca trwającej lekcji"
            )
        ),
        Entry(
            versionCode = 18,
            versionName = "0.6.5-beta",
            items = listOf(
                "Trwająca lekcja: ile zostało do końca - w planie (z paskiem), na stronie głównej i w widżetach",
                "Czas do lekcji (plan, strona główna, widżety) tylko w przerwie albo 30 min przed lekcją - nie w trakcie lekcji ani w okienku",
                "Zastępstwo znika z zakładki i strony głównej po swojej lekcji, w planie zostaje",
                "Zastępstwa innych grup do podejrzenia w zakładce Zastępstwa",
                "Bez powiadomień o zastępstwach, które już minęły; czytelniejsze powiadomienia z Twoimi nazwami przedmiotów",
                "Grupy WF nazwane Grupa 1 i Grupa 2 zamiast j1 i j2"
            )
        ),
        Entry(
            versionCode = 17,
            versionName = "0.6.4-beta",
            items = listOf(
                "WAŻNE: zastępstwa odczytywane w całości - część zastępstw i powiadomień mogła dotąd nie docierać"
            )
        ),
        Entry(
            versionCode = 16,
            versionName = "0.6.3-beta",
            items = listOf(
                "Płynniejsze przełączanie zakładek - bez migania innej zawartości przed właściwą",
                "Plan otwiera się od razu na właściwym dniu, bez przeskoku",
                "Kółka ładowania tylko przy dłuższym wczytywaniu"
            )
        ),
        Entry(
            versionCode = 15,
            versionName = "0.6.2-beta",
            items = listOf(
                "Plan: brakujące lekcje wpisane na stronie szkoły tekstem (np. zajęcia wojskowe w klasach F, zajęcia 5B i 5D)",
                "Zajęcia łączone pokazują, z którą klasą są (np. łączona z 1A)",
                "Poprawiona nazwa zajęć bez przypisanej sali (zaj.woj. zamiast zaj.woj.@)"
            )
        ),
        Entry(
            versionCode = 14,
            versionName = "0.6.1-beta",
            items = listOf(
                "Grupy WF: wybór grupy w klasach z podziałem na WF (np. zajęcia łączone z inną klasą)",
                "Aktualizacje pobierane i instalowane w aplikacji, z paskiem postępu i sprawdzeniem pliku",
                "Wybór grup w Ustawieniach można anulować - zmiany zapisują się dopiero po Zapisz",
                "Przezroczystość widżetów ustawiana suwakiem, od 0 do 100%",
                "Strona główna: dotknij najbliższej lekcji lub zastępstwa, aby przejść do planu lub zastępstw",
                "Poprawki stabilności"
            )
        ),
        Entry(
            versionCode = 13,
            versionName = "0.6.0-beta",
            items = listOf(
                "Przypomnienia przed lekcją - przed pierwszą albo każdą lekcją, 5-30 minut wcześniej",
                "Własne nazwy i kolory przedmiotów - w planie, na stronie głównej i w widżetach",
                "Kolor akcentu aplikacji i widżetów: pięć palet albo własny kolor",
                "Ciche godziny: powiadomienia bez dźwięku, np. w nocy",
                "Wybór ekranu startowego, zapamiętany widok dnia lub tygodnia",
                "Widżety: przezroczystość tła, nauczyciel i sala do wyboru",
                "Plan po lekcjach od razu pokazuje następny dzień, a zwolnienia z lekcji nie liczą się jako lekcje",
                "Przewijanie lekcji nie przełącza już przypadkiem dnia",
                "Raporty awarii i przycisk Zgłoś problem w Ustawieniach",
                "Czytelniejszy jasny motyw, uporządkowane Ustawienia i wiele poprawek"
            )
        ),
        Entry(
            versionCode = 12,
            versionName = "0.5.0-alpha",
            items = listOf(
                "Przesuń palcem w lewo lub prawo, aby przejść do innej sekcji",
                "Plan: przesuń w górę - następny dzień, w dół - poprzedni; po lekcjach od razu następny dzień",
                "Grupy: szybki wybór dla całego podziału, np. 1/2 dla wszystkich przedmiotów",
                "Strona główna (dawniej Start): pasek postępu lekcji i przerwy, informacja o nowej wersji",
                "Widżety: nowy widżet Zastępstwa, przerwy, kolory motywu i tapety, dotknięcie lekcji otwiera aplikację",
                "Kafelek w szybkich ustawieniach; w godzinach lekcji aplikacja otwiera się na planie",
                "Ogłoszenia: wyszukiwanie i starsze wpisy z archiwum strony szkoły",
                "Zastępstwa: na pierwszym planie nauczyciel zastępujący, uwagi widoczne w planie i widżetach",
                "Ustawienia: kolory z tapety i Wyczyść dane podręczne",
                "Wersja bez usług Google (dla F-Droid), płynniejsze działanie i poprawki"
            )
        ),
        Entry(
            versionCode = 11,
            versionName = "0.4.0-alpha",
            items = listOf(
                "Widzety: Nastepna lekcja i Plan dnia (odswiezane z dzwonkiem)",
                "Plan lekcji: przesun palcem w lewo lub prawo, aby zmienic dzien lub tydzien",
                "Aktualizacje nie kasuja juz lokalnych danych aplikacji",
                "Przygotowanie do wydania podpisanej wersji"
            )
        ),
        Entry(
            versionCode = 10,
            versionName = "0.3.1-alpha",
            items = listOf(
                "Nowe logo z litera e",
                "Lista klas w Ustawieniach przewija sie niezaleznie od reszty ekranu",
                "Usunieto oznaczanie ogloszen jako przeczytane",
                "Naprawiono wieczne ladowanie po zmianie klasy",
                "Naprawiono wiszace Synchronizacja po przerwanym syncu",
                "Mniej zapisow do bazy przy powiadomieniach"
            )
        ),
        Entry(
            versionCode = 9,
            versionName = "0.3.0-alpha",
            items = listOf(
                "Grupy zajeciowe: wybierz swoje grupy, a plan i Start pokaza tylko Twoje lekcje",
                "Religia i podobne zajecia: przelacznik pokazuj / ukryj",
                "Zastepstwa dla innej grupy nie sa juz pokazywane ani zglaszane w powiadomieniach",
                "Grupy mozna zmienic w dowolnej chwili w Ustawieniach",
                "Usunieto panel dewelopera"
            )
        ),
        Entry(
            versionCode = 8,
            versionName = "0.2.1-alpha",
            items = listOf(
                "Nowe logo aplikacji",
                "Odswiezony wyglad: duze tytuly, nowe karty, plynniejsze przejscia",
                "Kolory aplikacji zgodne z logo, w trybie jasnym i ciemnym",
                "Ustawienia w formie grupowanej listy",
                "Obrazki ogloszen na cala szerokosc karty",
                "Poprawiony link do repozytorium w O aplikacji",
                "Usunieto zakladke powiadomien i licznik na Ogloszeniach"
            )
        ),
        Entry(
            versionCode = 7,
            versionName = "0.2.0-alpha",
            items = listOf(
                "Historia powiadomien dostepna z Start (dzwonek z licznikiem nieprzeczytanych)",
                "Licznik nieprzeczytanych ogloszen na dolnym pasku",
                "Pociagnij w dol, aby odswiezyc - takze w Ogloszeniach",
                "Trwa teraz i Za X min aktualizuja sie na biezaco przy otwartym ekranie",
                "Klikniecie powiadomienia nie doklada juz kolejnych kopii ekranu",
                "Nowa ikona powiadomien na pasku stanu",
                "Mniejszy transfer danych dzieki cache HTTP (bez utraty swiezosci)",
                "Drobne poprawki stabilnosci"
            )
        ),
        Entry(
            versionCode = 6,
            versionName = "0.1.3-alpha",
            items = listOf(
                "Pociagnij w dol, aby odswiezyc - teraz tez w Planie lekcji i Zastepstwach",
                "Nowe zastepstwa od razu pojawiaja sie na planie i w Nastepnej lekcji",
                "Naprawiono powtarzajace sie powiadomienia o tych samych ogloszeniach",
                "Plan: dzien bez lekcji pokazuje Brak lekcji zamiast wiecznego ladowania",
                "Usunieto baner o odswiezaniu na Start"
            )
        ),
        Entry(
            versionCode = 5,
            versionName = "0.1.2-alpha",
            items = listOf(
                "Naprawiono brak najblizszej lekcji na Start w weekend i po ostatniej lekcji w piatek",
                "Naprawiono wieczne Ladowanie na Start, gdy pierwszy sync sie nie udal",
                "Lista klas w Ustawieniach jest przewijana i pokazuje od razu Twoja klase",
                "Ponowne wybranie tej samej klasy nie odswieza juz wszystkiego od nowa",
                "Mniej zapytan do serwera szkoly przy przegladaniu planu"
            )
        ),
        Entry(
            versionCode = 4,
            versionName = "0.1.1-alpha",
            items = listOf(
                "Naprawiono zastepstwa wszystkich klas pokazywane na Start tuz po wyborze klasy",
                "Naprawiono brak najblizszej lekcji na Start podczas pierwszego syncu",
                "Powiadomienia push przez FCM (wymaga wlasnej konfiguracji Firebase)",
                "Optymalizacja: mniej danych pobieranych z bazy przy kazdej zmianie planu"
            )
        ),
        Entry(
            versionCode = 3,
            versionName = "0.1.0-alpha",
            items = listOf(
                "Wersja Alpha — pierwsza po pełnym audycie kodu",
                "Naprawiono resetowanie 'przeczytane' na ogloszeniach po kazdym syncu",
                "Naprawiono powiadomienie o ogloszeniu prowadzace donikad",
                "Naprawiono nakladanie zastepstwa na zly dzien w widoku tygodnia",
                "Naprawiono dialog Co nowego, ktory nigdy sie nie pokazywal",
                "Zastepstwa usuniete na stronie szkoly znikaja teraz z apki",
                "Poprawiono wyscig przy rownoleglej synchronizacji (Setup/Ustawienia/Start)",
                "Drobne poprawki stabilnosci i higieny kodu"
            )
        ),
        Entry(
            versionCode = 2,
            versionName = "0.2.0",
            items = listOf(
                "Zmiana nazwy aplikacji na eLektron",
                "Naprawiono ladowanie planu i zastepstw po pierwszym uruchomieniu",
                "Zastepstwa pokazuja tylko Twoja klase, bez przeszlych",
                "Plan lekcji: przycisk Dzien/Tydzien, badge Za X min przed lekcja",
                "Pull-to-refresh w Start",
                "Powiadomienia grupowe w systemie",
                "Panel dewelopera tylko w wersji debug",
                "Drobne poprawki stabilnosci"
            )
        ),
        Entry(
            versionCode = 1,
            versionName = "0.1.0",
            items = listOf("Pierwsza wersja - plan lekcji, zastepstwa, ogloszenia")
        )
    )

    fun forVersion(code: Int): Entry? = entries.firstOrNull { it.versionCode == code }
    fun newerThan(sinceCode: Int): List<Entry> =
        entries.filter { it.versionCode > sinceCode }.sortedByDescending { it.versionCode }
}
