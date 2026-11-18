# Feed Fence

A tiny Android app that turns Instagram into stories + messages + people you follow.

- **Reels tab**: opening it sends you back to Home, and the tab button is covered.
- **Reels people send you**: they play. Swiping on to the *next* reel takes you back to the chat.
  (Same for a reel you open from a followed account's post or profile.)
- **Home feed**: suggested posts, suggestion carousels and ads are covered; a wall goes up after
  "You're all caught up".
- **Explore**: the grid is covered, the search bar still works.
- **Pause for 10 minutes** button, per-feature switches, and a debug "dump" button.

No permissions, no internet access, no data leaves the phone. It's an Accessibility Service that
reads Instagram's screen layout (view IDs) and draws covers on top. It doesn't modify Instagram.

## Install (Samsung)

Download `FeedFence-x.y.apk` from this repo's **Releases** page, then:

1. **Turn off Auto Blocker** (Samsung blocks app files by default):
   Settings > Security and privacy > Auto Blocker > OFF (turn it back on at the end).
2. **Install:** My Files > Downloads > tap the APK.
   - If asked, tap Settings > turn ON "Allow from this source" > back.
   - Tap Install. If Play Protect warns: "More details" > "Install anyway".
3. **Allow restricted settings:** open Feed Fence > "Turn on Feed Fence" > Installed apps > Feed Fence.
   If the switch is greyed out ("Restricted setting"): Settings > Apps > Feed Fence > ⋮ (top right)
   > "Allow restricted settings" > confirm.
4. **Turn it on:** Settings > Accessibility > Installed apps > Feed Fence > ON > Allow.
5. **Stop Samsung shutting it off:** Settings > Apps > Feed Fence > Battery > "Unrestricted".
6. **Clean up:** turn Auto Blocker back ON, and turn OFF "Install unknown apps" for the app you
   used to open the file (Settings > Apps > ⋮ > Special access > Install unknown apps).

Other Android phones: same idea, minus the Samsung-specific steps 1 and 5.

To pause: Feed Fence > "Pause for 10 minutes". To turn off: Settings > Accessibility > Feed Fence > OFF.

## When Instagram changes and something slips through

1. In Feed Fence, turn on **Show screen-dump button**.
2. In Instagram, go to the screen that isn't blocked properly and tap **dump**.
3. Send the file from `Downloads/FeedFence/` to whoever maintains this, along with what went wrong.
   The detection rules live at the top of `FenceService.java` (view-ID lists).

## Build

Needs: `aapt`, `dalvik-exchange`, `zipalign`, `apksigner` (Ubuntu: `apt install aapt dalvik-exchange
zipalign apksigner`), JDK 17+, and an API 34 `android.jar`.

```
ANDROID_JAR=/path/to/android-34.jar KEYSTORE=./feedfence-release.jks KS_PASS=<your password> VERSION_CODE=2 VERSION_NAME=1.1 ./build.sh
```

**Keep `feedfence-release.jks` safe and out of this repo** (it's in `.gitignore`). Android only installs an update over the existing app if it is
signed with the same key; a new key means uninstalling first and redoing the setup.
Keystore alias `feedfence`. The password is NOT stored here: it was given to you separately in chat. Keep it in a password manager, and keep this zip private.

## License

MIT, see `LICENSE`.
