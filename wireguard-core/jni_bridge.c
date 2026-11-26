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
    const char* uapi = jUapiConfig ? (*env)->GetStringUTFChars(env, jUapiConfig, NULL) : "";
    const char* addrs = jLocalAddrsCsv ? (*env)->GetStringUTFChars(env, jLocalAddrsCsv, NULL) : "";
    const char* dns = jDnsCsv ? (*env)->GetStringUTFChars(env, jDnsCsv, NULL) : "";

    char* res = wg_start_tunnel((char*)uapi, (char*)addrs, (char*)dns, (int)jMtu);

    if (jUapiConfig) (*env)->ReleaseStringUTFChars(env, jUapiConfig, uapi);
    if (jLocalAddrsCsv) (*env)->ReleaseStringUTFChars(env, jLocalAddrsCsv, addrs);
    if (jDnsCsv) (*env)->ReleaseStringUTFChars(env, jDnsCsv, dns);

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
    const char* hostPort = jHostPort ? (*env)->GetStringUTFChars(env, jHostPort, NULL) : "";
    char* res = wg_dial_test((char*)hostPort, (int)jTimeoutMs);
    if (jHostPort) (*env)->ReleaseStringUTFChars(env, jHostPort, hostPort);
    return to_jstring_and_free(env, res);
}
#endif
