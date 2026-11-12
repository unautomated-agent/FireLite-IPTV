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

These are exact first-request parameters from the initial snapshot, excluding the unavailable API key.
For each row the production fallback order is: original then Polish title at the given
year, then the same titles at year minus one, then year plus one. When no original is
available only the Polish/display title is sent. Duplicate normalized titles are removed.
The harness exports **every exact fallback query** as well as these first requests.

After the budget follow-up, local-only titles use the exact year only; original-title
candidates retain adjacent-year fallbacks. The table's first requests remain unchanged.

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

## Follow-up: playlist scope, priority and 400-request budget

### Scope hypothesis disproved

The 469-candidate initial audit was whole-feed future coverage, **not the production queue**.
`EpgDao.filmWindow(sourceId, from, until)` uses `MAPPED_PROGRAMMES`:

```sql
FROM channel c JOIN source s ON s.id = c.sourceId
JOIN channel_epg m ON m.sourceId = c.sourceId AND m.streamUrl = c.streamUrl
  AND s.epgGeneration = m.generation
JOIN programme p ON p.sourceId = m.sourceId AND p.generation = m.generation
  AND p.epgKey = m.epgKey
WHERE c.sourceId = :sourceId AND p.start < :until AND p.stop > :from
ORDER BY p.start, c.id
```

`FilmRepository` calls this with the active source ID and resolves results against that
source's `filmChannels`. Rankings use the same query and channel lookup. Unmatched public
EPG channels therefore cannot spend the user's budget. A SQLite control using the extracted
production query returned only the matched current-generation active-source row; an unmatched
programme, another source and a stale generation were excluded. No DAO change was necessary.

### Confirmed scheduling defect and change

Previously `ratingCandidates` stably partitioned the SQL order into the entire upcoming
48 hours, then archive. Thus 24–48h films could exhaust the budget before archive got a turn.
The new order is **airing/next-24h → archive → 24–48h**. Upcoming films sort by start;
archive sorts by most recent stop, with playlist position/channel ID breaking ties.
Deduplication happens after prioritization, preserving the highest-priority showing.
Candidates use exactly the union of the three ranking windows; expired/unsupported catch-up
and out-of-window films are excluded.

Successful movie matches already stopped all remaining fallbacks; a regression test now
explicitly covers this. Local-only titles now use **one exact-year request rather than three**.
Original-title candidates retain original/local exact-year and adjacent-year fallbacks
(up to six). This preserves original-title/year tolerance while trading away adjacent-year
coverage for local-only titles to conserve budget. Neither the 400/day cap nor caching changed.

### Real-feed simulation (not authenticated OMDb results)

`RealEpgBudgetAuditTest` uses production XMLTV parsing, candidate selection, `rankedFilms`,
query policy and `FilmLookup` with fake responses/store budget. The old schedule/query policy
is frozen in the harness for comparison. Input is sorted like SQL (`start, channel ID`), not
the feed's channel-alphabetic programme blocks. Cross-channel duplicate films share a cache
key; a lookup benefits every matching showing, regardless of its representative channel ID.

Same frozen audit time as above; include available archive history with a **synthetic seven-day
shift catch-up setting on every selected channel**, not a claim about provider capabilities.
The feed has 447 channels and 864 film showings in this wider interval. Cold-cache scenarios:

* **core7:** TVP1, Polsat, TVN, TVN7, Kino Polska, CANAL+1, HBO; 107 distinct visible films.
* **synthetic229:** those seven plus the lexicographically first 222 other feed IDs;
  469 distinct visible films. This deterministic stress subset is **not the user's unknown
  229-channel mapping**, nor a claim to reconstruct a typical playlist. Full IDs are exported.
* **all447:** counterfactual where every feed channel is actually in the playlist; 707 films.

Within at most 400 requests, distinct ranking-visible films receiving at least one query:

| Selected channels | Response assumption | Before | After | Requests before → after |
|---|---|---:|---:|---:|
| core7 | Every first request hits | 107 | 107 | 107 → 107 |
| core7 | Every request misses | 95 (94 completed) | 107 (all completed) | 400 → 324 |
| synthetic229 | Every first request hits | 400 | 400 | 400 → 400 |
| synthetic229 | Every request misses | 74 (73 completed) | 79 (78 completed) | 400 → 400 |
| all447 counterfactual | Every first request hits | 400 | 400 | 400 → 400 |
| all447 counterfactual | Every request misses | 74 (73 completed) | 80 (all completed) | 400 → 400 |

In the synthetic229 first-hit scenario, per-tab coverage `[next24h, 24–48h, archive]`
changes from **[158, 164, 121] to [158, 95, 190]**: archive coverage gains 69 films at the
intentional expense of tomorrow's coverage. Counts overlap when the same film is in multiple tabs.
For core7 with all misses, archive coverage rises from 30 to all 42 visible archive films.
All-miss counts are queries/negative-cache completions, **not successful ratings**.

Requests *before* the first lookup benefiting a film visible on each named channel,
synthetic229; range from all earlier first-request hits to all earlier requests missing:

| Channel | Before range | After range |
|---|---:|---:|
| HBO | 6–33 | 6–31 |
| CANAL+1 | 7–39 | 7–37 |
| Kino Polska | 22–120 | 22–112 |
| Polsat | 74–405 | 74–379 |
| TVN7 | 47–261 | 47–247 |
| TVP1 | 187–996 | 220–1050 |
| TVN | 203–1083 | 240–1125 |

Values above 399 mean the daily budget can be exhausted before that lookup. These are
scenario endpoints, not measured hit probabilities. Giving archive the requested priority
can delay some channels' first benefiting film; this is not a promise every channel improves.
HBO/CANAL+/Kino Polska still receive early queries, disproving a blanket explanation that
all such channels necessarily sit behind hundreds of unrelated requests.

**Conclusion:** playlist leakage is not present; wrong archive priority and avoidable
local-only fallback costs are fixed. Budget starvation of individual films is reachable and
demonstrated, but it does not establish why the real device had no ratings at all. Actual
229-channel IDs, catch-up configuration, cache and OMDb responses remain unavailable.

Run `*RealEpgBudgetAuditTest` with the same feed/time environment variables, additionally
setting `FIRELITE_BUDGET_AUDIT_OUTPUT` to an output path. It makes no network requests.
