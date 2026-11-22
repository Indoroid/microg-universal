package app.morphe.extension.microg.contract;

/**
 * Single source of truth shared by the GmsCompat-Stub app and the microG extension injected into
 * patched apps. Compiled into both; tools/e2e.sh fails if the two copies differ.
 * Lives under app/morphe/extension/microg/ so the patch never rewrites its strings.
 */
public final class MicroGContract {
    private MicroGContract() {
    }

    // Stub app and its broker provider.
    public static final String STUB_PACKAGE = "app.morphe.gmscompat";
    public static final String BROKER_AUTHORITY = STUB_PACKAGE + ".broker";
    public static final String BROKER_URI = "content://" + BROKER_AUTHORITY;
    public static final String ACTION_SETUP = STUB_PACKAGE + ".action.SETUP";

    // ContentResolver.call methods (arg = caller package).
    public static final String CALL_STATUS = "status";
    public static final String CALL_REGISTER = "register";

    // Result bundle keys.
    public static final String KEY_GMS_PACKAGE = "gms_package";
    public static final String KEY_GMS_INSTALLED = "gms_installed";
    public static final String KEY_GMS_VERSION = "gms_version";
    public static final String KEY_GMS_BATTERY_OPTIMIZED = "gms_battery_optimized";
    public static final String KEY_KEEPALIVE_RUNNING = "keepalive_running";
    public static final String KEY_PROMPTS_HANDLED = "prompts_handled";

    public static final String MICROG_DOWNLOAD_URL = "https://github.com/MorpheApp/MicroG-RE/releases/latest";

    /** GmsCore implementations, preferred first. Built at run time so no literal is a rewrite target. */
    public static String[] gmsCandidates() {
        String gms = ".android.gms";
        return new String[]{"app.revanced" + gms, "com.mgoogle" + gms, "com" + ".google" + gms};
    }

    // Signer SHA-256 digests.
    /** Official MicroG-RE releases (same value Morphe's GmsCoreSupportPatch checks). */
    public static final String MICROG_RE_SHA256 = "0b6c9515afb195fac59601696ba0a7907a0b217ccf720b43148427ccf64343e7";
    /** Google original release key (oldest lineage entry of GmsCore, Play Store signer, MicroG fake_signature). */
    public static final String GOOGLE_ORIGINAL_SHA256 = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83";
    /** Rotated GmsCore key (GrapheneOS gmscompat known signer). */
    public static final String GOOGLE_ROTATED_SHA256 = "5f2391277b1dbd489000467e4c2fa6af802430080457dce2f618992e9dfb5402";

    /** DER of the Google original certificate (SHA-256 {@link #GOOGLE_ORIGINAL_SHA256}). */
    public static final String GOOGLE_CERT_DER_BASE64 = "MIIEQzCCAyugAwIBAgIJAMLgh0ZkSjCNMA0GCSqGSIb3DQEBBAUAMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDAeFw0wODA4MjEyMzEzMzRaFw0zNjAxMDcyMzEzMzRaMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBAKtWLgDYO6IIrgqWbxJOKdoR8qtW0I9Y4sypEwPpt1TTcvZApxsdyxMJZ2JORland2qSGT2y5b+3JKkedxiLDmpHpDsz2WCbdxgxRczfey5YZnTJ4VZbH0xqWVW/8lGmPav5xVwnIiJS6HXk+BVKZF+JcWjAsb/GEuq/eFdpuzSqeYTcfi6idkyugwfYwXFU1+5fZKUaRKYCwkkFQVfcAs1fXA5V+++FGfvjJ/CxURaSxaBvGdGDhfXE28LWuT9ozCl5xw4Yq5OGazvV24mZVSoOO0yZ31j7kYvtwYK6NeADwbSxDdJEqO4k//0zOHKrUiGYXtqw/A0LFFtqoZKFjnkCAQOjgdkwgdYwHQYDVR0OBBYEFMd9jMIhF1Ylmn/Tgt9r45jk14alMIGmBgNVHSMEgZ4wgZuAFMd9jMIhF1Ylmn/Tgt9r45jk14aloXikdjB0MQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEUMBIGA1UEChMLR29vZ2xlIEluYy4xEDAOBgNVBAsTB0FuZHJvaWQxEDAOBgNVBAMTB0FuZHJvaWSCCQDC4IdGZEowjTAMBgNVHRMEBTADAQH/MA0GCSqGSIb3DQEBBAUAA4IBAQBt0lLO74UwLDYKqs6Tm8/yzKkEu116FmH4rkaymUIE0P9KaMftGlMexFlaYjzmB2OxZyl6euNXEsQH8gjwyxCUKRJNexBiGcCEyj6z+a1fuHHvkiaai+KL8W1EyNmgjmyy8AW7P+LLlkR+ho5zEHatRbM/YAnqGcFh5iZBqpknHf1SKMXFh4dd239FJ1jWYfbMDMy3NS5CTMQ2XFI1MvcyUTdZPErjQfTbQe3aDQsQcafEQPD+nqActifKZ0Np0IS9L9kR/wbNvyz6ENwPiTrjV2KRkEjH78ZMcUQXg0L3BYHJ3lc69Vs5Ddf9uUGGMYldX3WfMBEmh/9iFBDAaTCK";
}
