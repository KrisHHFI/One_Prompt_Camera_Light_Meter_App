# Film Meter (Camera Light Meter)

A simple reflected-light exposure meter for analog / film photography. It uses the phone's back camera to meter the scene, then tells you the shutter speed or aperture to set on your film camera.

## How to use

1. Set **ISO** to your film's box speed (or the speed you're rating it at).
2. Choose a **mode**:
   - **Aperture**: you pick the f-stop, and the app gives you the shutter speed.
   - **Shutter**: you pick the shutter speed, and the app gives you the f-stop.
3. Point the camera at your subject. The big number is the setting to use.
4. Tap **Hold** to freeze the reading, for example after metering something specific.

### Other controls

- **Average / Spot**: Average meters the whole frame. Spot meters only the circle in the middle, which is handy for metering a face, a mid-tone, or a grey card.
- **Compensation**: adds or removes exposure in ⅓ stops. Use **+** for bright scenes like snow or beach, or to overexpose negative film a bit. Use **−** for dark scenes. You can also use it to calibrate the app against a meter or camera you trust.
- The readout shows the **EV** for your chosen ISO. If the nearest marked shutter speed or f-stop is off by more than about ⅙ stop, it shows the difference (e.g. "+0.3 stop").

Shutter speeds use the full-stop values found on most film cameras (1/8000 s to 30 s). Apertures go in ⅓ stops from f/1.0 to f/64.
For exposures longer than about 1 second, remember **reciprocity failure**: most films need extra time. Check your film's datasheet.

Your settings are remembered between launches. The app only needs camera permission, and it has no ads and no internet access. It doesn't save any photos.

## How it works

The phone's auto-exposure meters the scene the way a classic reflected meter does, aiming for middle grey. The app reads back the exposure the phone chose (shutter time, ISO and lens aperture) and converts it to an exposure value (EV). It then converts that EV to settings for your film speed and chosen aperture or shutter.

## Get the APK

Every push to this repo builds the app with GitHub Actions and publishes `CameraLightMeter.apk` under **Releases**. Download it on your phone and tap it to install (allow installs from your browser; if Play Protect warns, choose "Install anyway").
