# Resident skin tool

Offline tools for making resident skins. None of this is part of the plugin jar.

A resident only appears in game once its everyday skin is in
`plugins/ProsperityNPC/resident-appearance.json`, signed by Mojang and checked against the
plugin's pinned key. These tools make the art and build that file. They never edit an existing file.

## Steps

1. **Author.** Write a `skins.yml` beside your staging folder (not in `residents.yml`):

   ```yaml
   version: 1
   skins:
     - key: otto-marsh
       slot: everyday
       look: {skin: "#C68642", hair: "#3B2A1A", eyes: "#2B4A6F", top: "#7A2E2E",
              trim: "#D9C9A3", legs: "#3A3A46", shoes: "#2A1F17", style: apron}
   ```

   `style` is `plain`, `apron` or `jacket`. Then:

   ```
   skins author skins.yml staging/
   ```

   This draws original 64x64 classic skins and writes `otto-marsh-everyday-v1.png`. An artist's
   64x64 RGBA classic PNG works too: name it the same way.

2. **Recolor.** Make work and leisure outfits from an everyday skin by changing clothing colours:

   ```
   skins recolor staging/otto-marsh-everyday-v1.png 7A2E2E=2E4A7A,D9C9A3=EEEEEE staging/otto-marsh-work-v1.png
   ```

   Only the body, arms and legs change. The head never changes, and a colour that is also on the
   head (skin tone, hair, eyes) is refused, so hands and faces keep one tone.

3. **Preview.** `python3 tools/skins/preview_server.py staging/` serves a page on 127.0.0.1.

4. **Approve.** The owner looks at the preview and accepts the look before anything is uploaded.

5. **Sign.** Check the PNGs first, then upload them:

   ```
   python3 tools/skins/sign.py candidates/ staging/*-v1.png
   python3 tools/skins/sign.py --upload candidates/ staging/*-v1.png
   ```

   Each approved PNG goes to MineSkin (unlisted, classic model) and comes back signed by Mojang.
   The tool then downloads the texture Mojang serves and checks every pixel against the PNG before
   it saves `candidates/<name>.json`:
   `{"key": "resident/v1/otto-marsh/everyday", "value": "...", "signature": "..."}`.
   Uploads are permanent, so only sign art the owner has approved. A PNG that already has a
   candidate is skipped, so a run that stops part way can be started again. An API key is optional
   (`--key-file`, readable by its owner only). Keep it out of the repo.

6. **Prepare.** Check every candidate and write a new manifest:

   ```
   skins prepare --out new-resident-appearance.json --residents residents.yml \
       --current resident-appearance.json candidates/*.json
   ```

   Each skin must be a key for a resident in the cast, appear once, verify against the plugin's
   pinned Mojang key and use the classic model. It prints a fingerprint per skin and the file's
   SHA-256. It refuses to overwrite.

7. **Install.** Turn `residents.cast-file-enabled` on first. With it off, a skin for anyone
   outside Mara, Ellis and Nico rejects the whole file and no resident is shown. Then copy the
   new file to `plugins/ProsperityNPC/resident-appearance.json` and fully restart. To turn the cast file
   off later, first install a file with only the generic skins and those three residents' skins.

## Running it

`skins` above means:

```
java -cp ProsperityNPC.jar:gson.jar:snakeyaml.jar tools/skins/java/com/prosperitysmp/npc/tools/skins/SkinTool.java <command> ...
```

Use the Gson and SnakeYAML jars that ship with your Paper server. On Windows use `;` between jars.

## Limits

- At most 114 skins in one file (18 generic occupation skins plus 3 for each of up to 32 residents).
- The manifest is at most 512 KiB.
- `staging/`, `candidates/`, `*.key` and receipts are git-ignored, at the repo root and in
  `tools/skins`.
