# Emulator fixture

Local test data for looking at FireLite on an Android TV emulator: 300 Polish-named channels in groups,
a five-day XMLTV guide for 200 of them, a looping live HLS test stream, and a few channels that answer 404.
Nothing here contacts a real provider.

```bash
mkdir -p seg && ffmpeg -f lavfi -i "testsrc2=size=1280x720:rate=25" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -t 60 -c:v libx264 -preset veryfast -g 50 -keyint_min 50 -sc_threshold 0 -b:v 1500k -c:a aac \
  -f hls -hls_time 2 -hls_list_size 0 -hls_segment_filename 'seg/s%03d.ts' seg/vod.m3u8
python3 server.py          # serves on port 8780
```

In the app add the playlist `http://10.0.2.2:8780/playlist.m3u`.

If the emulator clock differs from the host, start the server with
`FIXTURE_CLOCK_OFFSET=$(( $(adb shell date +%s) - $(date +%s) ))` so the guide lines up with the emulator's "now".

`tv.sh k <keycodes>` sends remote keys, `tv.sh s <name>` saves a screenshot, `tv.sh t` prints the visible texts.
