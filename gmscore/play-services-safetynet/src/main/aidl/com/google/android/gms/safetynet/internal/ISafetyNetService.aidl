package com.google.android.gms.safetynet.internal;

import android.os.Bundle;

import com.google.android.gms.safetynet.internal.ISafetyNetCallbacks;

interface ISafetyNetService {
    void attest(ISafetyNetCallbacks callbacks, in byte[] nonce) = 0;
    void attestWithApiKey(ISafetyNetCallbacks callbacks, in byte[] nonce, String apiKey) = 6;
    void getSharedUuid(ISafetyNetCallbacks callbacks) = 1;
    void lookupUri(ISafetyNetCallbacks callbacks, String apiKey, in int[] threatTypes, int version, String uri) = 2;
    void enableVerifyApps(ISafetyNetCallbacks callbacks) = 3;
    void listHarmfulApps(ISafetyNetCallbacks callbacks) = 4;
    void verifyWithRecaptcha(ISafetyNetCallbacks callbacks, String siteKey) = 5;

    void initSafeBrowsingForPackage(ISafetyNetCallbacks callbacks) = 8;
    void lookupApkDigest(ISafetyNetCallbacks callbacks, String apiKey, int mode, in byte[] digest) = 9;
    void reportHarmfulApp(int event, in Bundle extras) = 10;
    void initSafeBrowsing(ISafetyNetCallbacks callbacks) = 11;
    void shutdownSafeBrowsing() = 12;
    void isVerifyAppsEnabled(ISafetyNetCallbacks callbacks) = 13;
//
    void getSafeBrowsingDatabase(ISafetyNetCallbacks callbacks, int version, String apiKey) = 17;
    void getSafeBrowsingListState(ISafetyNetCallbacks callbacks, int threatType) = 18;
    void removeHarmfulApp(ISafetyNetCallbacks callbacks, String packageName, in byte[] digest) = 19;
    void getTrustToken(ISafetyNetCallbacks callbacks, in Bundle request) = 20;
}
