/* S15 spike/engine C helpers for the AudioQueue bindings
 * (tailtones.audio.coreservices.AudioQueue + CoreAudioEngine).
 *
 * Why raw C instead of Scala Native struct interop: the AudioQueue output
 * callback runs on a C-created, non-Scala thread (plan §6.7). Every field
 * read/write on AudioQueueBuffer / AudioStreamBasicDescription happens here
 * in plain C, so the Scala side only ever handles scalars and opaque pointers
 * — the callback body stays trivially alloc-free and GC-free, and we do not
 * depend on SN's struct-layout machinery for const-fielded Apple structs.
 *
 * Placement: src/main/resources/scala-native/ — the Scala Native 0.4+/0.5
 * convention for unmanaged C compiled into every binary that sees the
 * resource (same mechanism nativelib uses for its GC .c files).
 *
 * The callback (tq_output_callback) is pure C: counters, slot bookkeeping,
 * and CAS-claiming pre-filled buffers into AudioQueueEnqueueBuffer. It never
 * allocates and never enters managed code — §6.7 holds by construction
 * (docs/spikes/S15.md carries the spike evidence).
 *
 * LATENCY: macOS's AudioQueueBuffer has NO mPacketStartTime field (that
 * field existed only on iOS and is gone from the macOS SDK — verified against
 * the 26.x SDK headers, docs/spikes/S15.md). The documented equivalent used
 * here: a buffer's completion callback fires when the device finished
 * consuming it, so deviceStart ≈ done_host − bufferDuration, and
 * fill→device latency = done_host − fill_host − bufferDuration. Measured in
 * the mach_absolute_time domain (drain probe, see the spike doc §D).
 *
 * The whole AudioQueue/CoreAudio half is macOS-only; the Linux devcontainer
 * compiles this file too, so it sits behind __APPLE__ with stubs elsewhere.
 */
#ifndef __APPLE__
/* Scala Native compiles C with -std=c99; glibc then hides clock_gettime /
 * CLOCK_MONOTONIC / struct timespec from <time.h> unless the POSIX feature
 * macro is set BEFORE the include (container-verified — the darwin host never
 * compiles this branch, so the devcontainer is the only gate; docs/spikes/
 * S15.md). */
#define _POSIX_C_SOURCE 200809L
#endif
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <time.h>
#include <stdatomic.h>

#ifdef __APPLE__
#include <mach/mach_time.h>
#include <CoreAudio/CoreAudio.h>
#include <AudioToolbox/AudioToolbox.h>

/* ------------------------------------------------------- instance state ---
 * The queue instance's shared state block. malloc'ed by the Scala side,
 * passed to AudioQueueNewOutput as inUserData. EVERY field the output
 * callback touches lives here, written with plain atomic operations — the
 * callback is pure C and never enters managed code (plan §6.7).
 *
 * Slot ownership machine (mirrors tailtones.audio.coreservices.AudioQueue):
 *   FREE   mixer-owned, never yet filled
 *   READY  mixer filled it; claimable by the mixer poll or the callback
 *   QUEUED device-owned (inside the AudioQueue)
 *   DONE   device finished consuming; mixer refills
 * Claiming READY→QUEUED is a CAS; exactly one winner calls EnqueueBuffer.
 */
#define TQ_MAX_BUFS 8

typedef struct {
    void * volatile bufs[TQ_MAX_BUFS];
    volatile int32_t states[TQ_MAX_BUFS];
    volatile uint64_t enqueue_host[TQ_MAX_BUFS]; /* mach ticks at enqueue */
    volatile uint64_t done_host[TQ_MAX_BUFS];    /* mach ticks at callback */
    volatile uint64_t fill_host[TQ_MAX_BUFS];    /* mach ticks at mixer fill */
    volatile uint32_t done_bytes[TQ_MAX_BUFS];
    volatile uint64_t callback_count;
    volatile uintptr_t last_self;   /* pthread_self of last callback */
    volatile uintptr_t last_stack;  /* stack addr inside last callback */
    volatile uintptr_t first_self;  /* pthread_self of first callback */
    volatile int32_t same_thread;   /* 1 while every callback shared first_self */
    volatile int32_t queued;        /* buffers currently device-owned */
    volatile uint64_t starve_count; /* completion found nothing left to enqueue */
    uint32_t frame_bytes;           /* bytes of a full-size fill (bookkeeping) */
    int num_buffers;
} tq_state;

size_t ttaq_state_size(void) { return sizeof(tq_state); }
int ttaq_state_num_slots(void) { return TQ_MAX_BUFS; }

void ttaq_state_init(tq_state *st, int numBuffers) {
    memset(st, 0, sizeof(*st));
    if (numBuffers > TQ_MAX_BUFS) numBuffers = TQ_MAX_BUFS;
    st->num_buffers = numBuffers;
    st->same_thread = 1;
    for (int i = 0; i < TQ_MAX_BUFS; i++) st->states[i] = 0; /* FREE */
}

void ttaq_state_set_buf(tq_state *st, int slot, void *buf) {
    if (slot >= 0 && slot < TQ_MAX_BUFS) st->bufs[slot] = buf;
}
void ttaq_state_set_frame_bytes(tq_state *st, uint32_t bytes) {
    st->frame_bytes = bytes;
}
void *ttaq_state_buf(tq_state *st, int slot) {
    return (slot >= 0 && slot < TQ_MAX_BUFS) ? st->bufs[slot] : NULL;
}
int32_t ttaq_state_get_state(tq_state *st, int slot) {
    return __atomic_load_n(&st->states[slot], __ATOMIC_ACQUIRE);
}
void ttaq_state_set_state(tq_state *st, int slot, int32_t v) {
    __atomic_store_n(&st->states[slot], v, __ATOMIC_RELEASE);
}
void ttaq_state_stamp_enqueue(tq_state *st, int slot, uint64_t hostTime) {
    if (slot >= 0 && slot < TQ_MAX_BUFS) st->enqueue_host[slot] = hostTime;
}
void ttaq_state_stamp_fill(tq_state *st, int slot, uint64_t hostTime) {
    if (slot >= 0 && slot < TQ_MAX_BUFS) st->fill_host[slot] = hostTime;
}
uint64_t ttaq_state_enqueue_time(tq_state *st, int slot) {
    return st->enqueue_host[slot];
}
uint64_t ttaq_state_fill_time(tq_state *st, int slot) {
    return st->fill_host[slot];
}
uint64_t ttaq_state_done_time(tq_state *st, int slot) {
    return st->done_host[slot];
}
uint32_t ttaq_state_done_bytes(tq_state *st, int slot) {
    return st->done_bytes[slot];
}
uint64_t ttaq_state_callback_count(tq_state *st) {
    return __atomic_load_n(&st->callback_count, __ATOMIC_ACQUIRE);
}
uintptr_t ttaq_state_last_self(tq_state *st) { return st->last_self; }
uintptr_t ttaq_state_last_stack(tq_state *st) { return st->last_stack; }
uintptr_t ttaq_state_first_self(tq_state *st) { return st->first_self; }
int32_t ttaq_state_same_thread(tq_state *st) {
    return __atomic_load_n(&st->same_thread, __ATOMIC_ACQUIRE);
}
int32_t ttaq_queued_count(tq_state *st) {
    return __atomic_load_n(&st->queued, __ATOMIC_ACQUIRE);
}
uint64_t ttaq_starve_count(tq_state *st) {
    return __atomic_load_n(&st->starve_count, __ATOMIC_SEQ_CST);
}

/* CAS READY→QUEUED; the winner stamps host time, bumps queued and calls
 * AudioQueueEnqueueBuffer. Returns 1 on claim, 0 if someone else won (or not
 * READY), -1 on enqueue failure (rolled back to FREE). Called from BOTH the
 * mixer thread and the output callback — the CAS is the single serialization
 * point (no locks; the callback may never block on one).
 */
int ttaq_claim_and_enqueue(AudioQueueRef aq, tq_state *st, int slot) {
    if (slot < 0 || slot >= TQ_MAX_BUFS) return 0;
    int32_t expected = 1; /* READY */
    if (!__atomic_compare_exchange_n(&st->states[slot], &expected, 2, /* QUEUED */
                                     0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE))
        return 0;
    st->enqueue_host[slot] = mach_absolute_time();
    __atomic_fetch_add(&st->queued, 1, __ATOMIC_SEQ_CST);
    OSStatus stt = AudioQueueEnqueueBuffer(aq, (AudioQueueBufferRef)st->bufs[slot],
                                           0, NULL);
    if (stt != noErr) {
        __atomic_fetch_sub(&st->queued, 1, __ATOMIC_SEQ_CST);
        __atomic_store_n(&st->states[slot], 0 /* FREE */, __ATOMIC_RELEASE);
        return -1;
    }
    return 1;
}

/* ---------------------------------------------------------------- callback
 * AudioQueueOutputCallback. Pure C: counters + slot bookkeeping + claiming
 * pre-filled (READY) buffers for AudioQueueEnqueueBuffer. No malloc, no
 * Objective-C, no managed code — §6.7 holds by construction.
 */
void tq_output_callback(void *inUserData, AudioQueueRef inAQ,
                        AudioQueueBufferRef inBuffer) {
    tq_state *st = (tq_state *)inUserData;
    if (!st) return;
    __atomic_fetch_add(&st->callback_count, 1, __ATOMIC_SEQ_CST);
    uintptr_t self = (uintptr_t)pthread_self();
    volatile char probe_local;
    st->last_self = self;
    st->last_stack = (uintptr_t)&probe_local;
    uintptr_t want = __atomic_load_n(&st->first_self, __ATOMIC_ACQUIRE);
    if (want == 0) __atomic_store_n(&st->first_self, self, __ATOMIC_RELEASE);
    else if (want != self) __atomic_store_n(&st->same_thread, 0, __ATOMIC_RELEASE);

    int slot = -1;
    for (int i = 0; i < st->num_buffers; i++)
        if (st->bufs[i] == (void *)inBuffer) { slot = i; break; }
    if (slot < 0) return; /* foreign buffer — still GC-free */

    st->done_host[slot] = mach_absolute_time();
    st->done_bytes[slot] = inBuffer->mAudioDataByteSize;
    __atomic_store_n(&st->states[slot], 3 /* DONE */, __ATOMIC_RELEASE);
    __atomic_fetch_sub(&st->queued, 1, __ATOMIC_SEQ_CST);

    /* Hand the device its next buffer right here: claim the first READY
     * (mixer-prefilled) slot if one exists — pre-existing buffers only; the
     * callback never fills anything. */
    int claimed = 0;
    for (int i = 0; i < st->num_buffers; i++) {
        if (__atomic_load_n(&st->states[i], __ATOMIC_ACQUIRE) == 1 /* READY */ &&
            ttaq_claim_and_enqueue(inAQ, st, i) == 1) {
            claimed = 1;
            break;
        }
    }
    if (!claimed && __atomic_load_n(&st->queued, __ATOMIC_ACQUIRE) <= 0)
        __atomic_fetch_add(&st->starve_count, 1, __ATOMIC_SEQ_CST);
}

/* Stable function pointer for AudioQueueNewOutput. */
void *ttaq_callback_fn(void) { return (void *)&tq_output_callback; }

/* ------------------------------------------------------- AudioQueue helpers */

/* AudioStreamBasicDescription sized/aligned by C; fill for s16 mono PCM. */
size_t ttaq_asbd_size(void) { return sizeof(AudioStreamBasicDescription); }

void ttaq_fill_asbd(AudioStreamBasicDescription *asbd, uint32_t rate) {
    memset(asbd, 0, sizeof(*asbd));
    asbd->mSampleRate = (Float64)rate;
    asbd->mFormatID = kAudioFormatLinearPCM;
    asbd->mFormatFlags = kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked;
    asbd->mBytesPerPacket = 2;
    asbd->mFramesPerPacket = 1;
    asbd->mBytesPerFrame = 2;
    asbd->mChannelsPerFrame = 1;
    asbd->mBitsPerChannel = 16;
}

void *ttaq_buf_data(AudioQueueBufferRef buf) { return buf->mAudioData; }
void ttaq_buf_set_size(AudioQueueBufferRef buf, uint32_t bytes) {
    buf->mAudioDataByteSize = bytes;
}
uint32_t ttaq_buf_size(AudioQueueBufferRef buf) {
    return buf->mAudioDataByteSize;
}

/* ------------------------------------------------------------ spike probes */

/* pthread_self + address of a local (stack probe) of the CALLING thread. */
void ttaq_probe_self(uintptr_t *out_self, uintptr_t *out_stack) {
    volatile char probe_local;
    *out_self = (uintptr_t)pthread_self();
    *out_stack = (uintptr_t)&probe_local;
}

/* 1 if current pthread_self differs from the recorded one, else 0. */
int ttaq_probe_differs(uintptr_t recorded_self) {
    return pthread_self() != (pthread_t)recorded_self ? 1 : 0;
}

uint64_t ttaq_probe_host_time(void) { return mach_absolute_time(); }

int64_t ttaq_probe_mach_to_ns(uint64_t ticks) {
    static mach_timebase_info_data_t cached = {0, 0};
    if (cached.denom == 0) mach_timebase_info(&cached);
    return (int64_t)ticks * (int64_t)cached.numer / (int64_t)cached.denom;
}

/* Default output device id; 0 when the machine has no output device. */
uint32_t ttaq_probe_dev_avail(void) {
    AudioObjectID dev = kAudioObjectUnknown;
    AudioObjectPropertyAddress addr = {
        kAudioHardwarePropertyDefaultOutputDevice,
        kAudioObjectPropertyScopeGlobal,
        kAudioObjectPropertyElementMain};
    UInt32 sz = sizeof(dev);
    OSStatus st = AudioObjectGetPropertyData(
        kAudioObjectSystemObject, &addr, 0, NULL, &sz, &dev);
    return (st == noErr) ? (uint32_t)dev : 0u;
}

/* kAudioQueueProperty_IsRunning ('aqrn'): 1 while the queue is consuming.
 * Proves Start() actually put the queue on the device (spike §B evidence). */
int ttaq_probe_is_running(AudioQueueRef aq) {
    UInt32 running = 0;
    AudioQueuePropertyID prop = 'aqrn';
    UInt32 sz = sizeof(running);
    OSStatus st = AudioQueueGetProperty(aq, prop, &running, &sz);
    return (st == noErr) ? (int)running : -1;
}

/* kAudioQueueDeviceProperty_SampleRate ('aqsr'): device rate readback —
 * proves a real device is attached to the queue (spike §B evidence). */
double ttaq_probe_dev_rate(AudioQueueRef aq) {
    Float64 rate = 0;
    AudioQueuePropertyID prop = 'aqsr';
    UInt32 sz = sizeof(rate);
    OSStatus st = AudioQueueGetProperty(aq, prop, &rate, &sz);
    return (st == noErr) ? (double)rate : -1.0;
}

/* kAudioQueueProperty_EnableLevelMetering ('aqme'): UInt32 1. The meter is
 * measured on the queue's OUTPUT stage (after kAudioQueueParam_Volume), so
 * average power before/after a volume change is DEVICE-SIDE scaling evidence
 * (spike §C — the mic is TCC-silent, this is the machine-readable proof). */
int ttaq_probe_meter_enable(AudioQueueRef aq) {
    UInt32 on = 1;
    return (int)AudioQueueSetProperty(aq, 'aqme', &on, sizeof(on));
}

/* kAudioQueueProperty_CurrentLevelMeterDB ('aqmd'): array of
 * AudioQueueLevelMeterState, one per channel; mono → index 0. avg_out/peak_out
 * in dBFS. Returns 0 on success. */
int ttaq_probe_meter_read(AudioQueueRef aq, float *avg_out, float *peak_out) {
    AudioQueueLevelMeterState m = {0.f, 0.f};
    UInt32 sz = sizeof(m); /* 1 channel */
    OSStatus st = AudioQueueGetProperty(aq, 'aqmd', &m, &sz);
    if (st != noErr) return (int)st;
    *avg_out = m.mAveragePower;
    *peak_out = m.mPeakPower;
    return 0;
}

#else /* !__APPLE__ — Linux container build: stubs keep linking honest */

/* The Scala bindings reference the AudioQueue* C symbols directly (no @link;
 * macOS links them via -framework in build.sbt). On Linux these stubs
 * satisfy the linker — CoreAudioEngine's device gate (defaultOutputDevice ==
 * 0 here) prevents ever calling them at runtime. */
int AudioQueueNewOutput(void *f, void *cb, void *ud, void *rl, void *rlm,
                        unsigned fl, void **out) {
    (void)f; (void)cb; (void)ud; (void)rl; (void)rlm; (void)fl;
    if (out) *out = NULL;
    return -1; /* 'what' — unsupported on this platform */
}
int AudioQueueAllocateBuffer(void *aq, unsigned sz, void **out) {
    (void)aq; (void)sz;
    if (out) *out = NULL;
    return -1;
}
int AudioQueueEnqueueBuffer(void *aq, void *buf, unsigned n, void *descs) {
    (void)aq; (void)buf; (void)n; (void)descs;
    return -1;
}
int AudioQueueStart(void *aq, void *t) { (void)aq; (void)t; return -1; }
int AudioQueueStop(void *aq, char imm) { (void)aq; (void)imm; return -1; }
int AudioQueueDispose(void *aq, char imm) { (void)aq; (void)imm; return -1; }
int AudioQueueSetParameter(void *aq, int id, float v) {
    (void)aq; (void)id; (void)v;
    return -1;
}
int AudioQueueGetParameter(void *aq, int id, float *out) {
    (void)aq; (void)id;
    if (out) *out = 0.0f;
    return -1;
}

size_t ttaq_state_size(void) { return 8; }
int ttaq_state_num_slots(void) { return 0; }
void ttaq_state_init(void *st, int numBuffers) { (void)st; (void)numBuffers; }
void ttaq_state_set_buf(void *st, int slot, void *buf) {
    (void)st; (void)slot; (void)buf;
}
void ttaq_state_set_frame_bytes(void *st, uint32_t bytes) { (void)st; (void)bytes; }
void *ttaq_state_buf(void *st, int slot) { (void)st; (void)slot; return NULL; }
int32_t ttaq_state_get_state(void *st, int slot) { (void)st; (void)slot; return 0; }
void ttaq_state_set_state(void *st, int slot, int32_t v) {
    (void)st; (void)slot; (void)v;
}
void ttaq_state_stamp_enqueue(void *st, int slot, uint64_t h) {
    (void)st; (void)slot; (void)h;
}
void ttaq_state_stamp_fill(void *st, int slot, uint64_t h) {
    (void)st; (void)slot; (void)h;
}
uint64_t ttaq_state_enqueue_time(void *st, int slot) { (void)st; (void)slot; return 0; }
uint64_t ttaq_state_fill_time(void *st, int slot) { (void)st; (void)slot; return 0; }
uint64_t ttaq_state_done_time(void *st, int slot) { (void)st; (void)slot; return 0; }
uint32_t ttaq_state_done_bytes(void *st, int slot) { (void)st; (void)slot; return 0; }
uint64_t ttaq_state_callback_count(void *st) { (void)st; return 0; }
uintptr_t ttaq_state_last_self(void *st) { (void)st; return 0; }
uintptr_t ttaq_state_last_stack(void *st) { (void)st; return 0; }
uintptr_t ttaq_state_first_self(void *st) { (void)st; return 0; }
int32_t ttaq_state_same_thread(void *st) { (void)st; return 1; }
int32_t ttaq_queued_count(void *st) { (void)st; return 0; }
uint64_t ttaq_starve_count(void *st) { (void)st; return 0; }
int ttaq_claim_and_enqueue(void *aq, void *st, int slot) {
    (void)aq; (void)st; (void)slot;
    return -1;
}
void *ttaq_callback_fn(void) { return NULL; }
size_t ttaq_asbd_size(void) { return 32; }
void ttaq_fill_asbd(void *asbd, uint32_t rate) { (void)asbd; (void)rate; }
void *ttaq_buf_data(void *buf) { (void)buf; return NULL; }
void ttaq_buf_set_size(void *buf, uint32_t bytes) { (void)buf; (void)bytes; }
uint32_t ttaq_buf_size(void *buf) { (void)buf; return 0; }
void ttaq_probe_self(uintptr_t *out_self, uintptr_t *out_stack) {
    volatile char probe_local;
    *out_self = (uintptr_t)pthread_self();
    *out_stack = (uintptr_t)&probe_local;
}
int ttaq_probe_differs(uintptr_t recorded_self) {
    return pthread_self() != (pthread_t)recorded_self ? 1 : 0;
}
uint64_t ttaq_probe_host_time(void) { return 0; }
int64_t ttaq_probe_mach_to_ns(uint64_t ticks) { (void)ticks; return 0; }
uint32_t ttaq_probe_dev_avail(void) { return 0; }
int ttaq_probe_is_running(void *aq) { (void)aq; return -1; }
double ttaq_probe_dev_rate(void *aq) { (void)aq; return -1.0; }
int ttaq_probe_meter_enable(void *aq) { (void)aq; return -1; }
int ttaq_probe_meter_read(void *aq, float *a, float *p) {
    (void)aq;
    if (a) *a = -160.f;
    if (p) *p = -160.f;
    return -1;
}

#endif /* __APPLE__ */

/* Portable monotonic ns (both platforms). */
int64_t ttaq_probe_nanos(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}
