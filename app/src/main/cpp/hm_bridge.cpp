/*
 * Hosted Mirror — puente JNI entre el motor AirPlay de UxPlay y Android.
 *
 * Modelo "pull": los callbacks de UxPlay (hilos nativos) dejan video, audio y
 * eventos en colas; los hilos Java los sacan con nativeTake*(), así nunca hay
 * que adjuntar hilos nativos a la JVM.
 *
 * Copyright (C) 2026 Hosted. Licencia GPL-3.0 (deriva de UxPlay).
 */
#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "raop.h"
#include "dnssd.h"
#include "logger.h"
#include "stream.h"

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"

#define TAG "HostedMirror"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Tipos de evento: mantener en sincronía con NativeEvent.java */
enum {
    EV_CONN_INIT = 1,
    EV_CONN_DESTROY = 2,
    EV_CONN_RESET = 3,
    EV_CLIENT = 4,
    EV_VIDEO_START = 5,
    EV_VIDEO_SIZE = 6,
    EV_VIDEO_STOP = 7,
    EV_VIDEO_PAUSE = 8,
    EV_VIDEO_RESUME = 9,
    EV_AUDIO_FORMAT = 10,
    EV_AUDIO_FLUSH = 11,
    EV_VOLUME = 12,
};

/* Flags de MediaPacket: mantener en sincronía con MediaPacket.java */
enum {
    PKT_H265 = 1,
    PKT_KEYFRAME = 2,
};

/* Audio: el flag es el "ct" de AirPlay (1 = PCM S16LE ya decodificado, 4 = AAC-LC, 8 = AAC-ELD) */
enum { CT_PCM = 1, CT_ALAC = 2, CT_AAC_LC = 4, CT_AAC_ELD = 8 };

#define MISSED_FEEDBACK_LIMIT 15

struct Packet {
    std::vector<uint8_t> data;
    int64_t pts_us = 0;
    int flags = 0;
};

struct Event {
    int type = 0;
    int a = 0;
    int b = 0;
    float f = 0;
    std::string text;
};

template <typename T>
class BlockingQueue {
public:
    explicit BlockingQueue(size_t cap) : cap_(cap) {}

    void push(T &&item) {
        {
            std::lock_guard<std::mutex> lock(m_);
            if (q_.size() >= cap_) q_.pop_front();
            q_.push_back(std::move(item));
        }
        cv_.notify_one();
    }

    bool take(T &out, int timeout_ms) {
        std::unique_lock<std::mutex> lock(m_);
        cv_.wait_for(lock, std::chrono::milliseconds(timeout_ms), [&] { return !q_.empty(); });
        if (q_.empty()) return false;
        out = std::move(q_.front());
        q_.pop_front();
        return true;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(m_);
        q_.clear();
    }

private:
    std::mutex m_;
    std::condition_variable cv_;
    std::deque<T> q_;
    size_t cap_;
};

/*
 * Cola de video: el iPhone manda un keyframe (con SPS/PPS) solo al inicio o al
 * rotar, así que no se puede botar. Mientras nadie decodifica se guarda desde el
 * último keyframe; si se llena, se espera el próximo keyframe.
 */
class VideoQueue {
public:
    void push(Packet &&p) {
        {
            std::lock_guard<std::mutex> lock(m_);
            bool key = p.flags & PKT_KEYFRAME;
            if (key) {
                need_key_ = false;
                if (!attached_) q_.clear();
            } else if (need_key_) {
                return;
            }
            size_t cap = attached_ ? 240 : 1800;
            if (q_.size() >= cap) {
                LOGW("cola de video llena (%zu), esperando keyframe", q_.size());
                q_.clear();
                need_key_ = true;
                return;
            }
            q_.push_back(std::move(p));
        }
        cv_.notify_one();
    }

    bool take(Packet &out, int timeout_ms) {
        std::unique_lock<std::mutex> lock(m_);
        cv_.wait_for(lock, std::chrono::milliseconds(timeout_ms), [&] { return !q_.empty(); });
        if (q_.empty()) return false;
        out = std::move(q_.front());
        q_.pop_front();
        return true;
    }

    void set_attached(bool attached) {
        std::lock_guard<std::mutex> lock(m_);
        attached_ = attached;
    }

    void reset() {
        std::lock_guard<std::mutex> lock(m_);
        q_.clear();
        need_key_ = true;
    }

private:
    std::mutex m_;
    std::condition_variable cv_;
    std::deque<Packet> q_;
    bool attached_ = false;
    bool need_key_ = true;
};

static JavaVM *g_vm = nullptr;
static jclass g_packet_cls = nullptr;
static jmethodID g_packet_ctor = nullptr;
static jclass g_event_cls = nullptr;
static jmethodID g_event_ctor = nullptr;

static std::mutex g_server_mutex;
static raop_t *g_raop = nullptr;
static dnssd_t *g_dnssd = nullptr;
static unsigned short g_port = 0;

static VideoQueue g_video;
static BlockingQueue<Packet> g_audio(400);
static BlockingQueue<Event> g_events(200);

static std::atomic<int> g_open_connections{0};
static std::atomic<int> g_missed_feedback{0};
static std::atomic<bool> g_reset_httpd{false};
static std::atomic<bool> g_remove_connections{false};
static std::atomic<bool> g_supervisor_run{false};
static std::thread g_supervisor;
static std::mutex g_supervisor_mutex;
static std::condition_variable g_supervisor_cv;

static std::atomic<double> g_volume_db{0.0};
static std::atomic<bool> g_video_h265{false};

static std::mutex g_alac_mutex;
static std::unique_ptr<ALACDecoder> g_alac;

/* "Magic cookie" ALAC que usa AirPlay: 44100 Hz, 16 bits, 2 canales, 352 muestras por paquete */
static const uint8_t kAlacCookie[] = {
    0x00, 0x00, 0x00, 0x24, 'a', 'l', 'a', 'c', 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x01, 0x60, 0x00, 0x10, 0x28, 0x0a, 0x0e, 0x02, 0x00, 0xff,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xac, 0x44,
};

static void post_event(int type, int a = 0, int b = 0, float f = 0, const char *text = nullptr) {
    Event e;
    e.type = type;
    e.a = a;
    e.b = b;
    e.f = f;
    if (text) e.text = text;
    g_events.push(std::move(e));
}

static void wake_supervisor() {
    g_supervisor_cv.notify_all();
}

/* Detecta si el paquete Annex-B trae un keyframe (IDR) o parámetros SPS/VPS. */
static bool is_keyframe(const uint8_t *d, int len, bool h265) {
    for (int i = 0; i + 4 < len; i++) {
        if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
            uint8_t h = d[i + 3];
            if (h265) {
                int t = (h >> 1) & 0x3f;
                if (t == 32 || t == 33 || t == 19 || t == 20) return true;
            } else {
                int t = h & 0x1f;
                if (t == 5 || t == 7) return true;
            }
            i += 3;
        }
    }
    return false;
}

/* ===================== Callbacks de UxPlay ===================== */

extern "C" {

static void cb_conn_init(void *) {
    int n = ++g_open_connections;
    LOGI("conexión abierta (%d)", n);
    post_event(EV_CONN_INIT, n);
}

static void cb_conn_destroy(void *) {
    int n = --g_open_connections;
    if (n < 0) n = g_open_connections = 0;
    LOGI("conexión cerrada (%d)", n);
    if (n == 0) {
        g_missed_feedback = 0;
        g_video.reset();
        g_audio.clear();
    }
    post_event(EV_CONN_DESTROY, n);
}

static void cb_conn_feedback(void *) {
    g_missed_feedback = 0;
}

static void cb_conn_reset(void *, int reason) {
    LOGW("conn_reset motivo=%d", reason);
    g_reset_httpd = true;
    wake_supervisor();
    post_event(EV_CONN_RESET, reason);
}

static void cb_report_client_request(void *, char *deviceid, char *model, char *name, bool *admit) {
    LOGI("solicitud de %s (%s) id=%s", name ? name : "?", model ? model : "?", deviceid ? deviceid : "?");
    *admit = true;
    std::string text = std::string(name ? name : "") + "\n" + (model ? model : "");
    post_event(EV_CLIENT, 0, 0, 0, text.c_str());
}

static void cb_video_process(void *, raop_ntp_t *, video_decode_struct *data) {
    if (!data || !data->data || data->data_len <= 0) return;
    Packet p;
    p.data.assign(data->data, data->data + data->data_len);
    uint64_t t = data->ntp_time_local ? data->ntp_time_local : data->ntp_time_remote;
    p.pts_us = (int64_t) (t / 1000);
    p.flags = data->is_h265 ? PKT_H265 : 0;
    if (is_keyframe(data->data, data->data_len, data->is_h265)) p.flags |= PKT_KEYFRAME;
    g_video.push(std::move(p));
}

static int cb_video_set_codec(void *, video_codec_t codec) {
    bool h265 = codec == VIDEO_CODEC_H265;
    g_video_h265 = h265;
    LOGI("inicio de espejo, códec %s", h265 ? "H.265" : "H.264");
    post_event(EV_VIDEO_START, h265 ? 1 : 0);
    return 0;
}

static void cb_video_report_size(void *, float *ws, float *hs, float *w, float *h) {
    LOGI("tamaño de video %.0fx%.0f (fuente %.0fx%.0f)", *w, *h, *ws, *hs);
    post_event(EV_VIDEO_SIZE, (int) *w, (int) *h);
}

static void cb_video_reset(void *, reset_type_t type) {
    LOGI("video_reset tipo=%d", (int) type);
    g_video.reset();
    if (type == RESET_TYPE_RTP_SHUTDOWN || type == RESET_TYPE_NOHOLD) {
        g_remove_connections = true;
        wake_supervisor();
    }
    post_event(EV_VIDEO_STOP, (int) type);
}

static void cb_video_flush(void *) {
    g_video.reset();
}

static void cb_video_pause(void *) {
    post_event(EV_VIDEO_PAUSE);
}

static void cb_video_resume(void *) {
    post_event(EV_VIDEO_RESUME);
}

static void cb_audio_get_format(void *, unsigned char *ct, unsigned short *spf, bool *usingScreen,
                                bool *isMedia, uint64_t *audioFormat) {
    LOGI("formato de audio ct=%d spf=%d pantalla=%d media=%d", *ct, *spf, *usingScreen, *isMedia);
    if (*ct == CT_ALAC) {
        std::lock_guard<std::mutex> lock(g_alac_mutex);
        g_alac.reset(new ALACDecoder());
        if (g_alac->Init((void *) kAlacCookie, sizeof(kAlacCookie)) != 0) {
            LOGE("no se pudo iniciar el decodificador ALAC");
            g_alac.reset();
        }
    }
    g_audio.clear();
    /* Java solo ve PCM (1) o AAC (4/8): ALAC se decodifica aquí */
    int out_ct = (*ct == CT_ALAC) ? CT_PCM : *ct;
    post_event(EV_AUDIO_FORMAT, out_ct, *spf, *usingScreen ? 1.0f : 0.0f);
}

static void cb_audio_process(void *, raop_ntp_t *, audio_decode_struct *data) {
    if (!data || !data->data || data->data_len <= 0) return;
    Packet p;
    if (data->ct == CT_ALAC) {
        std::lock_guard<std::mutex> lock(g_alac_mutex);
        if (!g_alac) return;
        BitBuffer bits;
        BitBufferInit(&bits, data->data, (uint32_t) data->data_len);
        uint32_t frames = g_alac->mConfig.frameLength; /* 352 en AirPlay */
        if (frames == 0 || frames > 4096) return;
        uint8_t pcm[4096 * 4];
        uint32_t samples = 0;
        if (g_alac->Decode(&bits, pcm, frames, 2, &samples) != 0 || samples == 0) return;
        p.data.assign(pcm, pcm + samples * 4);
        p.flags = CT_PCM;
    } else {
        p.data.assign(data->data, data->data + data->data_len);
        p.flags = data->ct;
    }
    p.pts_us = (int64_t) (data->ntp_time_remote / 1000);
    g_audio.push(std::move(p));
}

static void cb_audio_flush(void *) {
    g_audio.clear();
    post_event(EV_AUDIO_FLUSH);
}

static double cb_audio_set_client_volume(void *) {
    return g_volume_db.load();
}

static void cb_audio_set_volume(void *, float volume) {
    g_volume_db = volume;
    post_event(EV_VOLUME, 0, 0, volume);
}

static void cb_audio_set_metadata(void *, const void *, int) {}
static void cb_audio_set_coverart(void *, const void *, int) {}
static void cb_audio_stop_coverart_rendering(void *) {}
static void cb_audio_set_progress(void *, uint32_t *, uint32_t *, uint32_t *) {}
static void cb_display_pin(void *, char *) {}
static void cb_register_client(void *, const char *, const char *, const char *) {}
static bool cb_check_register(void *, const char *) { return true; }
static const char *cb_passwd(void *, int *len) {
    *len = 0;
    return nullptr;
}
static void cb_export_dacp(void *, const char *, const char *) {}
static void cb_on_video_play(void *, const char *, const float) {}
static void cb_on_video_scrub(void *, const float) {}
static void cb_on_video_rate(void *, const float) {}
static void cb_on_video_stop(void *) {}
static void cb_on_video_acquire_playback_info(void *, playback_info_t *) {}
static float cb_on_video_playlist_remove(void *) { return 0.0f; }

static void cb_log(void *, int level, const char *msg) {
    int prio = ANDROID_LOG_DEBUG;
    if (level <= LOGGER_ERR) prio = ANDROID_LOG_ERROR;
    else if (level == LOGGER_WARNING) prio = ANDROID_LOG_WARN;
    else if (level <= LOGGER_INFO) prio = ANDROID_LOG_INFO;
    __android_log_write(prio, "UxPlay", msg);
}

} /* extern "C" */

/* ===================== Supervisor ===================== */

/*
 * Hace lo que el main loop de UxPlay hace entre sesiones: reinicia el servidor
 * HTTP tras una caída, limpia conexiones al terminar el espejo y vigila el
 * "latido" del cliente (llega cada 2 s; si falta 15 s, la conexión murió).
 */
static void supervisor_loop() {
    auto last_tick = std::chrono::steady_clock::now();
    while (g_supervisor_run) {
        {
            std::unique_lock<std::mutex> lock(g_supervisor_mutex);
            g_supervisor_cv.wait_for(lock, std::chrono::milliseconds(500));
        }
        if (!g_supervisor_run) break;

        auto now = std::chrono::steady_clock::now();
        if (now - last_tick >= std::chrono::seconds(1)) {
            last_tick = now;
            if (g_open_connections > 0) {
                int missed = ++g_missed_feedback;
                if (missed > MISSED_FEEDBACK_LIMIT) {
                    LOGW("sin latido del cliente por %d s: reiniciando", missed);
                    g_missed_feedback = 0;
                    g_reset_httpd = true;
                }
            } else {
                g_missed_feedback = 0;
            }
        }

        std::lock_guard<std::mutex> lock(g_server_mutex);
        if (!g_raop) continue;
        if (g_reset_httpd.exchange(false)) {
            g_remove_connections = false;
            LOGI("reiniciando servidor AirPlay");
            raop_stop_httpd(g_raop);
            raop_remove_known_connections(g_raop);
            g_video.reset();
            g_audio.clear();
            unsigned short port = raop_get_port(g_raop);
            raop_start_httpd(g_raop, &port);
            raop_set_port(g_raop, port);
            post_event(EV_VIDEO_STOP, -1);
        } else if (g_remove_connections.exchange(false)) {
            raop_remove_known_connections(g_raop);
        }
    }
}

/* ===================== Ciclo de vida del servidor ===================== */

static void set_features(dnssd_t *d) {
    /* Mismo set que UxPlay por defecto (pairing AirPlay 2, sin HLS, sin H.265) */
    static const int on[] = {1, 2, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 25, 28, 30};
    static const int off[] = {0, 3, 4, 8, 23, 24, 26, 27, 29, 31, 42};
    for (int b : on) dnssd_set_airplay_features(d, b, 1);
    for (int b : off) dnssd_set_airplay_features(d, b, 0);
}

static void stop_server_locked() {
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
    }
    if (g_raop) {
        raop_destroy(g_raop);
        g_raop = nullptr;
    }
    if (g_dnssd) {
        dnssd_destroy(g_dnssd);
        g_dnssd = nullptr;
    }
    g_port = 0;
    g_open_connections = 0;
    g_video.reset();
    g_audio.clear();
}

static int start_server_locked(const char *name, const std::vector<char> &hw_addr, const char *device_id,
                               const char *keyfile, int width, int height, int fps) {
    int err = 0;
    g_dnssd = dnssd_init(name, (int) strlen(name), hw_addr.data(), (int) hw_addr.size(), 0, &err);
    if (err || !g_dnssd) {
        LOGE("dnssd_init falló: %d", err);
        g_dnssd = nullptr;
        return 1;
    }
    set_features(g_dnssd);

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.video_report_size = cb_video_report_size;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec = cb_video_set_codec;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;

    g_raop = raop_init(&cbs);
    if (!g_raop) {
        LOGE("raop_init falló");
        return 2;
    }
    raop_set_log_callback(g_raop, cb_log, nullptr);
    raop_set_log_level(g_raop, LOGGER_INFO);
    if (raop_init2(g_raop, 1, device_id, keyfile)) {
        LOGE("raop_init2 falló");
        free(g_raop);
        g_raop = nullptr;
        return 3;
    }
    raop_set_plist(g_raop, "width", width);
    raop_set_plist(g_raop, "height", height);
    raop_set_plist(g_raop, "refreshRate", 60);
    raop_set_plist(g_raop, "maxFPS", fps);

    unsigned short tcp[3] = {0, 0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);

    unsigned short port = raop_get_port(g_raop);
    if (raop_start_httpd(g_raop, &port) < 0) {
        LOGE("no se pudo abrir el servidor HTTP");
        return 4;
    }
    raop_set_port(g_raop, port);
    g_port = port;
    raop_set_dnssd(g_raop, g_dnssd);

    if ((err = dnssd_register_raop(g_dnssd, port))) {
        LOGE("dnssd_register_raop falló: %d", err);
        return 5;
    }
    if ((err = dnssd_register_airplay(g_dnssd, port))) {
        LOGE("dnssd_register_airplay falló: %d", err);
        return 6;
    }
    LOGI("Hosted Mirror publicado como \"%s\" en el puerto %u (features 0x%llX)", name, port,
         (unsigned long long) dnssd_get_airplay_features(g_dnssd));
    return 0;
}

/* ===================== JNI ===================== */

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return std::string();
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

static jobject make_packet(JNIEnv *env, const Packet &p) {
    jbyteArray arr = env->NewByteArray((jsize) p.data.size());
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, (jsize) p.data.size(), (const jbyte *) p.data.data());
    jobject obj = env->NewObject(g_packet_cls, g_packet_ctor, arr, (jlong) p.pts_us, (jint) p.flags);
    env->DeleteLocalRef(arr);
    return obj;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass pc = env->FindClass("cl/hosted/mirror/engine/MediaPacket");
    jclass ec = env->FindClass("cl/hosted/mirror/engine/NativeEvent");
    if (!pc || !ec) return JNI_ERR;
    g_packet_cls = (jclass) env->NewGlobalRef(pc);
    g_event_cls = (jclass) env->NewGlobalRef(ec);
    g_packet_ctor = env->GetMethodID(g_packet_cls, "<init>", "([BJI)V");
    g_event_ctor = env->GetMethodID(g_event_cls, "<init>", "(IIIFLjava/lang/String;)V");
    if (!g_packet_ctor || !g_event_ctor) return JNI_ERR;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_cl_hosted_mirror_engine_NativeBridge_nativeStart(JNIEnv *env, jclass, jstring jname, jbyteArray jhw,
                                                      jstring jdevice_id, jstring jkeyfile, jstring jhost,
                                                      jint width, jint height, jint fps) {
    std::lock_guard<std::mutex> lock(g_server_mutex);
    if (g_raop) return 0;

    std::string name = jstr(env, jname);
    std::string device_id = jstr(env, jdevice_id);
    std::string keyfile = jstr(env, jkeyfile);
    std::string host = jstr(env, jhost);
    std::vector<char> hw(env->GetArrayLength(jhw));
    env->GetByteArrayRegion(jhw, 0, (jsize) hw.size(), (jbyte *) hw.data());

    setenv("HM_MDNS_HOST", host.c_str(), 1);
    int r = start_server_locked(name.c_str(), hw, device_id.c_str(), keyfile.c_str(), width, height, fps);
    if (r) {
        stop_server_locked();
        return r;
    }
    if (!g_supervisor_run.exchange(true)) {
        g_supervisor = std::thread(supervisor_loop);
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_cl_hosted_mirror_engine_NativeBridge_nativeStop(JNIEnv *, jclass) {
    if (g_supervisor_run.exchange(false)) {
        wake_supervisor();
        if (g_supervisor.joinable()) g_supervisor.join();
    }
    std::lock_guard<std::mutex> lock(g_server_mutex);
    stop_server_locked();
    LOGI("servidor detenido");
}

extern "C" JNIEXPORT jint JNICALL
Java_cl_hosted_mirror_engine_NativeBridge_nativeGetPort(JNIEnv *, jclass) {
    return g_port;
}

extern "C" JNIEXPORT void JNICALL
Java_cl_hosted_mirror_engine_NativeBridge_nativeDisconnect(JNIEnv *, jclass) {
    g_reset_httpd = true;
    wake_supervisor();
}

extern "C" JNIEXPORT void JNICALL
Java_cl_hosted_mirror_engine_NativeBridge_nativeSetVideoAttached(JNIEnv *, jclass, jboolean attached) {
    g_video.set_attached(attached);
}

extern "C" JNIEXPORT jobject JNICALL
Java_cl_hosted_mirror_engine_NativeBridge_nativeTakeVideo(JNIEnv *env, jclass, jint timeout_ms) {
    Packet p;
    if (!g_video.take(p, timeout_ms)) return nullptr;
    return make_packet(env, p);
}

extern "C" JNIEXPORT jobject JNICALL
Java_cl_hosted_mirror_engine_NativeBridge_nativeTakeAudio(JNIEnv *env, jclass, jint timeout_ms) {
    Packet p;
    if (!g_audio.take(p, timeout_ms)) return nullptr;
    return make_packet(env, p);
}

extern "C" JNIEXPORT jobject JNICALL
Java_cl_hosted_mirror_engine_NativeBridge_nativeTakeEvent(JNIEnv *env, jclass, jint timeout_ms) {
    Event e;
    if (!g_events.take(e, timeout_ms)) return nullptr;
    jstring text = env->NewStringUTF(e.text.c_str());
    jobject obj = env->NewObject(g_event_cls, g_event_ctor, (jint) e.type, (jint) e.a, (jint) e.b, (jfloat) e.f, text);
    env->DeleteLocalRef(text);
    return obj;
}
