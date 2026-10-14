# Kody SMS

Aplikacja na Androida, która zastępuje makra z MacroDroid. Działa na telefonie z kartą firmową.

## Jak działa

1. Bank wysyła SMS z kodem na telefon z kartą.
2. Aplikacja wyciąga numer operacji i wysyła **do wszystkich planistów** SMS:
   `Nowy kod nr 15. Aby go otrzymać, wyślij SMS: TAK 15`
   (kod i hasło **nie** są w tej wiadomości).
3. Planista odpisuje `TAK 15` (albo samo `TAK`, gdy czeka tylko jeden kod).
4. **Pierwszy**, kto odpisze, dostaje kod: `Kod nr 15: 111111`.
   Każdy kolejny dostaje: `Kod nr 15 został już przejęty przez Jan.`
5. Każde zdarzenie (kto, który kod, kiedy) zapisuje się w dzienniku w aplikacji. Same kody nie trafiają do dziennika.

Kilka zakupów naraz działa poprawnie: każdy kod jest osobno, a `TAK 16` zawsze da kod nr 16.
Gdy czeka kilka kodów i ktoś wyśle samo `TAK`, aplikacja poprosi o numer.

Dodatkowo:
- kod wygasa po ustawionej liczbie minut (domyślnie 15; 0 = bez limitu),
- ten sam planista może poprosić o swój kod ponownie (`TAK 15`),
- odpowiedzi od numerów spoza listy planistów są ignorowane,
- zdublowany SMS z banku (telefony czasem dostarczają dwa razy) jest pomijany,
- zapisane kody są usuwane z telefonu po 24 godzinach.

## Jak dostać plik APK (bez instalowania czegokolwiek na komputerze)

1. Załóż darmowe konto na github.com i utwórz nowe repozytorium (może być prywatne).
2. Wgraj do niego **całą zawartość** tego folderu (przycisk *Add file → Upload files*; folder `.github` musi się wgrać razem z resztą).
3. Wejdź w zakładkę **Actions → Zbuduj APK**. Po kilku minutach build się kończy.
4. Wejdź w zakończony build i pobierz plik **KodySMS-apk** (w środku jest `app-debug.apk`).

Alternatywa: otwórz folder w Android Studio i wybierz *Build → Build APK(s)*.

## Instalacja na telefonie z kartą

1. Skopiuj `app-debug.apk` na telefon i zainstaluj (Android poprosi o zgodę na instalację z nieznanych źródeł).
2. **Android 13 i nowszy:** aplikacje spoza Sklepu Play nie mogą od razu dostać uprawnień SMS.
   Wejdź w *Ustawienia → Aplikacje → Kody SMS → ⋮ (trzy kropki) → Zezwól na ustawienia z ograniczeniami*.
3. Otwórz aplikację i nadaj uprawnienia SMS (przycisk na górze).
4. Dotknij *Wyłącz oszczędzanie baterii dla aplikacji* i zezwól. Na telefonach Xiaomi, Samsung, Huawei, Oppo włącz też autostart / „pozwól działać w tle” w ustawieniach producenta. Bez tego system może usypiać aplikację i SMS-y zostaną przeoczone.
5. Wpisz ustawienia i dotknij **Zapisz ustawienia**:
   - **Nadawca SMS z banku**: dokładnie tak, jak telefon wyświetla nadawcę (np. `Millennium`). Można kilka, po przecinku.
   - **Planiści**: jedna osoba w linii, `Imię;numer`, np. `Jan;+48 600 100 200`. Aplikacja pokaże pod listą, kogo rozpoznała.
6. **Wyłącz makra MacroDroid i starą aplikację do przekierowywania SMS-ów**, żeby kody nie wychodziły równolegle.
7. Dotknij **Test: udaj SMS z banku**. Aplikacja wyśle prawdziwy SMS do planistów z numerem operacji z zakresu 900–999. Poproś jedną osobę o odpisanie `TAK <numer>` i sprawdź dziennik.

## Ograniczenia, o których trzeba wiedzieć

- **Tylko Android.** Na iPhonie aplikacja nie odczyta SMS-ów w tle.
- Telefon z kartą musi być włączony, naładowany i mieć zasięg. Aplikacja nie uruchomi się sama, jeśli ją „wymusisz zatrzymaj” w ustawieniach.
- Numer nadawcy SMS można podrobić. Ktoś, kto zna numer planisty, mógłby teoretycznie wysłać `TAK` z jego numeru. Do wewnętrznego użytku w firmie to akceptowalne ryzyko, ale nie jest to zabezpieczenie na poziomie banku.
- Każdy wychodzący SMS kosztuje według Twojego pakietu. Przy 5 planistach: 5 SMS-ów na powiadomienie + 1 na kod.
- Wiadomości z polskimi znakami mają 70 znaków na część (zamiast 160), dłuższe wysyłają się jako kilka części.
- Przy telefonie z dwiema kartami SIM SMS-y wychodzą z karty domyślnej dla wiadomości.
- Instalacja spoza Sklepu Play może wywołać ostrzeżenie Google Play Protect (aplikacja prosi o uprawnienia SMS). To oczekiwane przy aplikacji własnej.
- Przekazywanie kodów SMS innym osobom to zwykle naruszenie regulaminu karty. Przy sporze o transakcję bank może odmówić zwrotu. Sprawdź regulamin; bezpieczniejsze są dodatkowe karty z osobnymi numerami telefonów.

## Dla programisty

- Cała logika jest w `Core.kt` i nie używa Androida. Testy: `app/src/test/.../EngineTest.kt` (26 przypadków, w tym kilka zakupów naraz, wygasanie, duplikaty, obcy numer).
- Format SMS z banku rozpoznają wyrażenia regularne w obiekcie `Parser` (`Operacja nr`, `HasłoSMS`, `sprzedawca`, `kwota`). Gdyby bank zmienił format, zmiana jest w jednym miejscu.
- Build: Gradle 8.9, Android Gradle Plugin 8.5.2, Kotlin 1.9.24, `minSdk 26`, `targetSdk 34`. Brak zewnętrznych bibliotek.
