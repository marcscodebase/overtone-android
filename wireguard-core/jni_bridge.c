#ifdef __ANDROID__
#include <jni.h>
#include <stdlib.h>

extern char* wg_start_tunnel(char* uapiConfig, char* localAddrsCsv, char* dnsCsv, int mtu);
extern char* wg_stop_tunnel(void);
extern char* wg_get_status(void);
extern char* wg_dial_test(char* hostPort, int timeoutMs);

static jstring to_jstring_and_free(JNIEnv* env, char* native_str) {
    if (native_str == NULL) {
        return (*env)->NewStringUTF(env, "{}");
    }
    jstring result = (*env)->NewStringUTF(env, native_str);
    free(native_str);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_musicplayer_core_network_wireguard_WireGuardNativeBridge_nativeStartTunnel(
    JNIEnv* env,
    jobject thiz,
    jstring jUapiConfig,
    jstring jLocalAddrsCsv,
    jstring jDnsCsv,
    jint jMtu
) {
    (void)thiz;
    const char* uapi = "";
    const char* addrs = "";
    const char* dns = "";

    if (jUapiConfig != NULL) {
        uapi = (*env)->GetStringUTFChars(env, jUapiConfig, NULL);
        if (uapi == NULL) {
            return NULL;
        }
    }
    if (jLocalAddrsCsv != NULL) {
        addrs = (*env)->GetStringUTFChars(env, jLocalAddrsCsv, NULL);
        if (addrs == NULL) {
            if (jUapiConfig != NULL) (*env)->ReleaseStringUTFChars(env, jUapiConfig, uapi);
            return NULL;
        }
    }
    if (jDnsCsv != NULL) {
        dns = (*env)->GetStringUTFChars(env, jDnsCsv, NULL);
        if (dns == NULL) {
            if (jLocalAddrsCsv != NULL) (*env)->ReleaseStringUTFChars(env, jLocalAddrsCsv, addrs);
            if (jUapiConfig != NULL) (*env)->ReleaseStringUTFChars(env, jUapiConfig, uapi);
            return NULL;
        }
    }

    char* res = wg_start_tunnel((char*)uapi, (char*)addrs, (char*)dns, (int)jMtu);

    if (jDnsCsv != NULL) (*env)->ReleaseStringUTFChars(env, jDnsCsv, dns);
    if (jLocalAddrsCsv != NULL) (*env)->ReleaseStringUTFChars(env, jLocalAddrsCsv, addrs);
    if (jUapiConfig != NULL) (*env)->ReleaseStringUTFChars(env, jUapiConfig, uapi);

    return to_jstring_and_free(env, res);
}

JNIEXPORT jstring JNICALL
Java_com_musicplayer_core_network_wireguard_WireGuardNativeBridge_nativeStopTunnel(
    JNIEnv* env,
    jobject thiz
) {
    (void)thiz;
    return to_jstring_and_free(env, wg_stop_tunnel());
}

JNIEXPORT jstring JNICALL
Java_com_musicplayer_core_network_wireguard_WireGuardNativeBridge_nativeGetStatus(
    JNIEnv* env,
    jobject thiz
) {
    (void)thiz;
    return to_jstring_and_free(env, wg_get_status());
}

JNIEXPORT jstring JNICALL
Java_com_musicplayer_core_network_wireguard_WireGuardNativeBridge_nativeDialTest(
    JNIEnv* env,
    jobject thiz,
    jstring jHostPort,
    jint jTimeoutMs
) {
    (void)thiz;
    const char* hostPort = "";
    if (jHostPort != NULL) {
        hostPort = (*env)->GetStringUTFChars(env, jHostPort, NULL);
        if (hostPort == NULL) {
            return NULL;
        }
    }
    char* res = wg_dial_test((char*)hostPort, (int)jTimeoutMs);
    if (jHostPort != NULL) (*env)->ReleaseStringUTFChars(env, jHostPort, hostPort);
    return to_jstring_and_free(env, res);
}
#endif
