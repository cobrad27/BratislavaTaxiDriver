# Android build továreň

Repozitár, ktorý z každého projektu v `apps/` zostaví APK.

```
apps/
  uber-pomocnik/     <- Gradle projekt
  dalsia-appka/      <- pridaním priečinka pribudne appka
.github/workflows/build.yml
```

## Ako to funguje

Po každom pushnutí workflow nájde všetky priečinky v `apps/`, zostaví ich
paralelne a z každého spraví **Release** s priloženým APK.

Stiahnutie: záložka **Releases** -> posledný build -> `.apk` pod *Assets*.
Dá sa to ťuknúť aj z mobilu. APK je podpísané ladiacim kľúčom, na vlastné
používanie to stačí.

Ak build spadne, chyba je v **Actions** -> posledný beh -> červený krok.

## Appky

### uber-pomocnik

Služba prístupnosti, ktorá číta ponuku jazdy v Uber Driver a pri nevyhovujúcej
vzdialenosti stlačí X. Filtre: dojazd k zákazníkovi, dĺžka jazdy, minimálna
cena, iba hotovosť. Vzdialenosti sa nastavujú posuvníkom s krokom 0,5 km.

**Po inštalácii:** otvor appku -> *Otvoriť nastavenia prístupnosti* -> zapni ju.
Nastav filtre, prepni *Zapnuté*, ale **nechaj zapnuté "Nasucho"**, kým si
v logu neoveríš, že ponuku číta správne. V tomto režime nič neklikne, len
zapisuje, čo by spravila.

**Ladenie:** v appke dole je *Test filtra bez Uberu* - vložíš riadky textu
a uvidíš, čo z nich prečítala a ako by rozhodla. Netreba na to jazdu.

Automatické ovládanie appky vodiča je proti podmienkam Uberu - môže prísť
pozastavenie účtu. Dáta nikam neodchádzajú, všetko beží v telefóne.
