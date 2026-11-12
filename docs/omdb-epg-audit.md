# Build-24 real EPG / OMDb audit

Downloaded `https://iptv-epg.org/files/epg-pl.xml.gz` on 2026-11-12.
SHA-256: `248e509970573309559e678df7cb723007fda32d0636bfe73ceff6a9aa06f2d8`.
Frozen audit window: **2026-11-12 13:02:08Z to 2026-11-14 13:02:08Z**.

`RealEpgAuditTest` invokes production `XmlTvParser.parse`, `Films.candidate`,
`ratingCandidates`, `Films.queries` and `OmdbHttpClient.movieQuery`.
It does not make network requests. Channels are synthetic, without catch-up;
this measures airing/upcoming films across the entire public feed, not archive films
or the subset matched to a particular user's playlist. Example order is XML order,
not the device's database order. Public feed download time is frozen for reproducibility.

## Counts

- 32,964 programmes overlapping the window.
- 541 film showings; 366 have original titles.
- 469 distinct enrichment candidates (normalized original/local title + year).
- 320 have original titles (68.2%); 149 do not.
- XMLTV `xx` denotes the original title, not necessarily English. The production parser
  prefers `xx`, otherwise English, separately from the Polish display title.

## Fifteen real queries

These are exact first-request parameters, excluding the unavailable API key.
For each row the production fallback order is: original then Polish title at the given
year, then the same titles at year minus one, then year plus one. When no original is
available only the Polish/display title is sent. Duplicate normalized titles are removed.
The harness exports **every exact fallback query** as well as these first requests.

| Polish/display title | XMLTV original | First query |
|---|---|---|
| Liberator 2 | Under Siege 2: Dark Territory | `t=Under+Siege+2%3A+Dark+Territory&y=1995&type=movie` |
| Hazardowe szaleństwo | Pari dispari (Italian) | `t=Pari+dispari&y=1978&type=movie` |
| Był sobie chłopiec | About a Boy | `t=About+a+Boy&y=2002&type=movie` |
| W pokoju obok | The Room Next Door | `t=The+Room+Next+Door&y=2024&type=movie` |
| Agent | Chief of Station | `t=Chief+of+Station&y=2024&type=movie` |
| Zabić, jak to łatwo powiedzieć | Killing Them Softly | `t=Killing+Them+Softly&y=2012&type=movie` |
| Kobieta na dachu | none | `t=Kobieta+na+dachu&y=2022&type=movie` |
| Wszyscy święci | none | `t=Wszyscy+%C5%9Bwi%C4%99ci&y=2002&type=movie` |
| Długi weekend | none | `t=D%C5%82ugi+weekend&y=2004&type=movie` |
| P.S. Kocham Cię | P.S. I Love You | `t=P.S.+I+Love+You&y=2007&type=movie` |
| Sicario | none (display title already international) | `t=Sicario&y=2015&type=movie` |
| Angielska robota | The Bank Job | `t=The+Bank+Job&y=2008&type=movie` |
| Szef roku | El buen patrón (Spanish) | `t=El+buen+patr%C3%B3n&y=2021&type=movie` |
| Niebo Montany | Montana Sky | `t=Montana+Sky&y=2007&type=movie` |
| Pokuta | Atonement | `t=Atonement&y=2007&type=movie` |

These English title/year pairs are plausible movie matches, and Sicario needs no translated
title. Polish-only and non-English originals depend on OMDb alias coverage; a miss is plausible.
**No authenticated matching or hit rate was measured.** It is not justified to blame all
missing ratings on Polish-only EPG data: most candidates already have original titles.

## Response and pipeline review

Exactly one public no-key request was made:

```text
GET http://www.omdbapi.com/?t=About+a+Boy&y=2002&type=movie
HTTP/1.1 401 Unauthorized
{"Response":"False","Error":"No API key provided."}
```

Production `OmdbHttpClient.lookup` maps any non-200 to `HTTP_<status>`. Successful-body
decoding already handles `imdbRating="N/A"` as an absent rating and `imdbVotes="N/A"`
as zero. `Response="False"` maps `Movie not found!` to a miss, `Request limit reached!`
to `REMOTE_LIMIT`, and `Invalid API key!` / `No API key provided.` to `AUTH`.
An HTTP 401 is still recorded as `HTTP_401`, not treated as a movie miss or cached.
New tests cover these exact bodies, malformed/empty bodies and actual loopback HTTP 401.

`FilmRepository` starts enrichment independently of ranking-screen subscription.
`enrichFilms` stops the batch on AUTH/HTTP_401, remote limit or the local budget.
Such a stop previously slept for an hour, and saving an unchanged key emitted no change.
An externally activated key therefore could not trigger an immediate retry through Save.
**Fixed:** every Save now emits a refresh event, including for an identical key.
The repository observes that event and re-reads the latest key before starting enrichment.

Previously the ranking/settings UI only said the key was configured; HTTP 401 was visible
only as a raw diagnostics code. **Fixed:** rankings and settings display safe current error
messages, explicitly explaining key rejection/email activation and quota failures.
An activation-help resource explains that Save retries immediately. Current failures clear
on a successful response or Save, while historical `lastError` remains in diagnostics.
No key or response body is put into these messages.

Cache writes and rendering use the same `Films.key` (normalized original title, or local
title, plus year). Positive results persist for 30 days; genuine misses for seven days;
transport/auth failures do not poison the cache. `ratings` is eagerly collected from Room
and combined into `catalogue`; `FilmRankingsScreen` observes catalogue changes and rebuilds
`rankedFilms`. Unrated rows are not filtered out. A new continuing-collection test verifies
ratings arriving after subscription become available without another minute tick, including
the cache-key lookup and ranking result. This is not an on-device Room/UI rendering test.

469 distinct films across the *entire* public feed exceed the 400 HTTP-request/day budget
even with perfect first-request hits. Fallbacks consume that budget too, so a cold cache can
take more than one day. This can explain partial coverage, not prove the reported absence of
all ratings: the user's channel subset, cache, budget and OMDb responses are unavailable.

**The real device's missing-rating root cause remains unverified.** We verified the
reachable authentication/activation UX and same-key retry defects, but not whether that
device actually received HTTP 401. The safe OMDb diagnostics counters/error code distinguish
authentication, quota, misses and a queue that never starts without exposing the key.

## Reproduce

Download the feed to a persistent location, then from the repository root:

```sh
FIRELITE_REAL_EPG=/path/to/epg-pl.xml.gz \
FIRELITE_EPG_AUDIT_NOW=2026-11-12T13:02:08Z \
FIRELITE_EPG_AUDIT_OUTPUT=/path/to/real-epg-queries.txt \
bash ./gradlew -I /home/ubuntu/firelite-local-repositories.init.gradle \
  testDebugUnitTest --tests '*RealEpgAuditTest'
```

Use a current audit time for a fresh download; the frozen feed will eventually expire.
Without `FIRELITE_REAL_EPG`, the optional real-feed audit is skipped; ordinary deterministic
film, parser, HTTP, preference and rendering-format tests remain offline.
