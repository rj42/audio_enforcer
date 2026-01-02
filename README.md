Here is the brutal, no-nonsense version.

***

# 🛡️ Bluetooth Audio Enforcer

**Forces Android to keep audio on your DAC while using car steering wheel controls.**

### 🎯 The Goal
*   **Audio Output:** High-end Bluetooth DAC (LDAC / aptX HD).
*   **Controls:** Car steering wheel buttons (Next/Prev/Pause).
*   **The Problem:** Default Android logic switches audio output to the Car speakers whenever you press a steering wheel button.
*   **Why not Dual Audio?** Dual Audio forces the low-quality SBC codec to sync streams. We want pure Hi-Res.

### ⚡ How It Works
1.  Connect both the Car (for buttons) and the DAC (for sound).
2.  This app runs as a relentless background service.
3.  It monitors the Bluetooth A2DP stack.
4.  If the Car attempts to hijack the audio stream, the app immediately forces it back to the DAC using internal Android APIs (`setActiveDevice` via Reflection).

### ✨ Features
*   **Hard-coded Priority:** Zero configuration UI. It just works.
*   **Auto-Start:** Runs automatically on boot. Can't be killed easily (`START_STICKY`).
*   **Hidden API:** Uses system-level methods to override default Bluetooth routing logic.

### ⚙️ Setup
Open `EnforcerService.kt` and replace the MAC addresses with yours:

```kotlin
private val CAR_MAC = "XX:XX:XX:XX:XX:XX" // Your Car (The Hijacker)
private val DAC_MAC = "YY:YY:YY:YY:YY:YY" // Your DAC (The Output)
```

**Build. Install. Forget.**
