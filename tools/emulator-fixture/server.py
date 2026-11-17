#!/usr/bin/env python3
"""FireLite emulator fixture: 300-channel M3U, XMLTV and one looping live HLS. Emulator URL: http://10.0.2.2:8780/playlist.m3u"""
import time, os, html, datetime as dt
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
ROOT = os.path.dirname(os.path.abspath(__file__)); N = 30; DUR = 2
GROUPS = {"Ogólne": ["TVP 1 HD","TVP 2 HD","Polsat HD","TVN HD","TV4 HD","TV Puls HD","TVN 7 HD","TV6 HD","Super Polsat HD","TTV HD"],
 "Informacyjne": ["TVN24 HD","TVP Info HD","Polsat News HD","TV Republika HD","TVN24 BiS HD","Polsat News 2","wPolsce24 HD","Biznes24 HD"],
 "Sport": ["Eleven Sports 1 HD","Eleven Sports 2 HD","Polsat Sport 1 HD","Polsat Sport 2 HD","Canal+ Sport HD","Canal+ Sport 2 HD","TVP Sport HD","Eurosport 1 HD","Eurosport 2 HD","Sportklub HD"],
 "Filmy i seriale": ["HBO HD","HBO 2 HD","HBO 3 HD","Canal+ Premium HD","Canal+ Film HD","FilmBox Premium HD","Kino Polska HD","AXN HD","Warner TV HD","Polsat Film HD","Stopklatka HD","Ale kino+ HD"],
 "Dokumentalne": ["Discovery Channel HD","National Geographic HD","Nat Geo Wild HD","Planete+ HD","BBC Earth HD","History HD","Animal Planet HD","TVP Historia HD"],
 "Dla dzieci": ["MiniMini+ HD","Cartoon Network HD","Nickelodeon HD","Disney Channel HD","TVP ABC HD","Boomerang HD"],
 "Muzyka": ["Eska TV HD","Polo TV","4fun.tv","MTV Polska HD","Disco Polo Music","Stars.tv"],
 "Radio": ["RMF FM","Radio ZET","Trójka","Radio Łódź"]}
REGIONS = ["Warszawa","Kraków","Łódź","Wrocław","Poznań","Gdańsk","Szczecin","Bydgoszcz","Lublin","Białystok","Katowice","Rzeszów","Olsztyn","Opole","Kielce","Gorzów Wielkopolski"]
TITLES = [("Wiadomości","Najważniejsze informacje dnia z kraju i ze świata.",30),("Harry Potter i Kamień Filozoficzny","Film przygodowy. Młody czarodziej rozpoczyna naukę w Hogwarcie.",150),
 ("Ojciec chrzestny","Dramat gangsterski, USA 1972. Reż. Francis Ford Coppola.",175),("Na dobre i na złe","Serial obyczajowy o lekarzach ze szpitala w Leśnej Górze.",55),
 ("Liga Mistrzów: studio","Zapowiedź wieczornych meczów.",45),("Milionerzy","Teleturniej.",40),("Incepcja","Thriller science fiction, USA 2010. Reż. Christopher Nolan.",148),
 ("Pogoda","Prognoza pogody.",10),("Sprawa dla reportera","Program interwencyjny Elżbiety Jaworowicz.",60),("Skazani na Shawshank","Dramat, USA 1994.",142),
 ("Planeta Ziemia","Serial przyrodniczy BBC.",50),("Ranczo","Serial komediowy.",50)]
def channels():
    out = []
    for g, names in GROUPS.items():
        for n in names: out.append((n, g))
    for i, r in enumerate(REGIONS): out.append((f"TVP3 {r}", "Regionalne"))
    i = 1
    while len(out) < 300:
        out.append((f"Kanał tematyczny {i:03d} HD" if i % 7 else f"+Kanał bardzo długa nazwa do sprawdzenia ucinania tekstu {i:03d}", ["Pozostałe","Zagraniczne","4K UHD"][i % 3])); i += 1
    return out
CH = channels()
def playlist(host):
    lines = [f'#EXTM3U url-tvg="http://{host}/epg.xml"']
    for i, (n, g) in enumerate(CH):
        arch = ' timeshift="3"' if i % 3 != 2 else ''
        path = "dead" if n == "TV Republika HD" or i % 53 == 52 else "live"
        lines.append(f'#EXTINF:-1 tvg-id="ch{i}" tvg-name="{n}" group-title="{g}"{arch},{n}')
        lines.append(f"http://{host}/{path}/{i}/index.m3u8")
    return "\n".join(lines) + "\n"
def epg():
    now = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=int(os.environ.get("FIXTURE_CLOCK_OFFSET", "0")))).replace(minute=0, second=0, microsecond=0)
    o = ['<?xml version="1.0" encoding="UTF-8"?><tv>']
    for i, (n, _) in enumerate(CH[:200]): o.append(f'<channel id="ch{i}"><display-name>{html.escape(n)}</display-name></channel>')
    for i in range(200):
        t = now - dt.timedelta(days=3); k = i
        while t < now + dt.timedelta(days=2):
            title, desc, mins = TITLES[k % len(TITLES)]; k += 1
            e = t + dt.timedelta(minutes=mins)
            o.append(f'<programme start="{t:%Y%m%d%H%M%S} +0000" stop="{e:%Y%m%d%H%M%S} +0000" channel="ch{i}"><title lang="pl">{html.escape(title)}</title><desc lang="pl">{html.escape(desc)}</desc></programme>')
            t = e
    o.append("</tv>"); return "\n".join(o)
def live():
    seq = int(time.time() // DUR)
    out = ["#EXTM3U","#EXT-X-VERSION:3",f"#EXT-X-TARGETDURATION:{DUR}",f"#EXT-X-MEDIA-SEQUENCE:{seq-5}"]
    for s in range(seq - 5, seq + 1):
        if s % N == 0: out.append("#EXT-X-DISCONTINUITY")
        out += [f"#EXTINF:{DUR}.0,", f"/seg/s{s % N:03d}.ts"]
    return "\n".join(out) + "\n"
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def send(self, code, body, ctype="text/plain"):
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        p = self.path.split("?")[0]; host = self.headers.get("Host", "10.0.2.2:8780")
        if p == "/playlist.m3u": self.send(200, playlist(host).encode(), "audio/x-mpegurl")
        elif p == "/epg.xml": self.send(200, epg().encode(), "application/xml")
        elif p.startswith("/live/"): self.send(200, live().encode(), "application/vnd.apple.mpegurl")
        elif p.startswith("/seg/") and os.path.isfile(ROOT + p): self.send(200, open(ROOT + p, "rb").read(), "video/mp2t")
        else: self.send(404, b"not found")
    def log_message(self, f, *a): print(time.strftime("%H:%M:%S"), self.address_string(), f % a, flush=True)
ThreadingHTTPServer(("0.0.0.0", 8780), H).serve_forever()
