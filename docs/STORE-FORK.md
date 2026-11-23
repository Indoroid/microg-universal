# store/: what differs from Aurora Store

Base: `AuroraOSS/AuroraStore` at `12840634f` (4.8.4, code 76), imported as one squashed commit.
Application ID `com.aurora.store.microg`, so it installs next to a stock Aurora Store. No billing,
no Play setup/restore, no YouTube-specific code.

| Change | Content | Where |
|---|---|---|
| Patch before install | Finished downloads are merged (base + config splits), run through the universal microG patch (and the clone patch by default), signed with the store's own key and handed to the installer | `data/patcher/MicroGPatcher.kt`, `DownloadWorker` |
| Install as clone / original | Clone (default): package renamed to `<package>.morphe`, providers and content URIs rewritten, so it installs next to the original. Original: same package name, replaces an existing install only if signed with the same key | Settings → Installation → *microG* |
| Patch for microG | Open or share any APK with the store → patched and installed the same way | `data/patcher/PatchApkActivity.kt` (`VIEW` / `SEND` of `application/vnd.android.package-archive`) |
| Updates | Patched apps and clones are checked against their Play package (recorded in the patch's meta-data) | `UpdateWorker` |
| Install sessions | size hint for the staged copy, next session continues after a shared library | `SessionInstaller` |
| Memory | `largeHeap` for patching large apps on the phone | `AndroidManifest.xml` |

## Settings (Installation → microG)

| Setting | Default | Effect |
|---|---|---|
| Patch apps for microG | on | apps that depend on Play services (as reported by Play) and `com.google.*` apps are patched before installation; off installs them unmodified |
| Install as clone | on | clone package name; off keeps the original package name |

## Build inputs

The store compiles the patch library from `patches/` (classes on the classpath, extension `.dex` files as
Java resources) and `app.morphe:morphe-patcher:2.5.0` from Maven local (built from source, see the
README). Upstream's GitLab CI files are kept but unused; this repository builds with GitHub Actions.

Update from upstream:

```bash
git fetch https://gitlab.com/AuroraOSS/AuroraStore.git master
```

```bash
git merge -s subtree -Xsubtree=store --allow-unrelated-histories FETCH_HEAD
```
