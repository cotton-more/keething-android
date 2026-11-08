/*
 * The bridge of NativeArgon2 (keething-spec unlock.faster-argon2): the reference Argon2 run on a thread of its own,
 * so that the caller can wait for it a millisecond at a time and give it up between two waits (AC4).
 *
 * A derivation is start, then await until it is done (stop between, to give it up), then always finish: it joins the
 * thread, hands out the result and wipes and frees everything (AC5). The working memory is wiped by the reference
 * code's free_memory; the password's copy as soon as it is hashed (ARGON2_FLAG_CLEAR_PASSWORD) and again here; H0
 * and the last block by the reference code. What the compression leaves on a stack is on the derivation's own thread
 * or a lane worker's, whose stacks are unmapped when they are joined.
 *
 * The lanes are filled by workers that live as long as the derivation and meet after every slice, not by the
 * reference code's threads started anew for each slice: Android puts a new thread on a slow core until it has seen it
 * busy, and two lanes then took longer than one (the Pixel 6, 2026-11-07).
 */
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "argon2.h"
#include "core.h"

/* finish's result for a derivation given up; the others are the reference code's (ARGON2_OK, its errors). */
#define GIVEN_UP (-100)

typedef struct {
    pthread_t thread;
    pthread_mutex_t lock;
    pthread_cond_t ended;
    int done; /* under lock */
    int result; /* under lock */
    atomic_int given_up;
    argon2_type type;
    argon2_context context;
} job_t;

/* Where the lane workers wait for each other after a slice. */
typedef struct {
    pthread_mutex_t lock;
    pthread_cond_t turn;
    uint32_t count; /* the workers, under lock */
    uint32_t waiting;
    uint32_t generation;
    int halt; /* given up, as the last to arrive saw it */
} meeting_t;

/* Waits for every worker; whether the derivation was given up, the same answer for all of them. */
static int meet(meeting_t *meeting, const argon2_instance_t *instance) {
    pthread_mutex_lock(&meeting->lock);
    uint32_t generation = meeting->generation;
    if (++meeting->waiting == meeting->count) {
        meeting->waiting = 0;
        meeting->generation++;
        meeting->halt = argon2_given_up(instance);
        pthread_cond_broadcast(&meeting->turn);
    } else {
        while (generation == meeting->generation) pthread_cond_wait(&meeting->turn, &meeting->lock);
    }
    int halt = meeting->halt;
    pthread_mutex_unlock(&meeting->lock);
    return halt;
}

typedef struct {
    const argon2_instance_t *instance;
    meeting_t *meeting;
    uint32_t first; /* the first of its lanes; then every stride-th */
    const uint32_t *stride; /* the workers there are, read after the first meeting */
} worker_t;

static void *work(void *argument) {
    worker_t *worker = argument;
    const argon2_instance_t *instance = worker->instance;
    if (meet(worker->meeting, instance)) return NULL; /* every worker started */
    uint32_t stride = *worker->stride;
    for (uint32_t pass = 0; pass < instance->passes; ++pass) {
        for (uint32_t slice = 0; slice < ARGON2_SYNC_POINTS; ++slice) {
            for (uint32_t lane = worker->first; lane < instance->lanes; lane += stride) {
                argon2_position_t position = {pass, lane, (uint8_t)slice, 0};
                fill_segment(instance, position);
            }
            if (meet(worker->meeting, instance)) return NULL;
        }
    }
    return NULL;
}

/*
 * Fills the memory with up to instance->threads workers, this thread one of them; fewer when no more threads can be
 * started.
 */
static void fill(argon2_instance_t *instance) {
    uint32_t threads = instance->threads, started = 0, stride;
    pthread_t thread[threads];
    worker_t workers[threads];
    meeting_t meeting = {.count = threads};
    pthread_mutex_init(&meeting.lock, NULL);
    pthread_cond_init(&meeting.turn, NULL);
    for (uint32_t i = 0; i < threads; i++) workers[i] = (worker_t){instance, &meeting, i, &stride};
    while (started + 1 < threads && pthread_create(&thread[started + 1], NULL, work, &workers[started + 1]) == 0) {
        started++;
    }
    pthread_mutex_lock(&meeting.lock);
    stride = started + 1;
    meeting.count = stride; /* the workers not started never meet */
    pthread_mutex_unlock(&meeting.lock);
    work(&workers[0]);
    for (uint32_t i = 1; i <= started; i++) pthread_join(thread[i], NULL);
    pthread_cond_destroy(&meeting.turn);
    pthread_mutex_destroy(&meeting.lock);
}

/* The steps of the reference code's argon2_ctx, its memory filled by fill; the memory freed on every way out. */
static int run(job_t *job) {
    argon2_context *context = &job->context;
    argon2_instance_t instance;
    uint32_t segment_length;
    int result = validate_inputs(context);
    if (result != ARGON2_OK) return result;

    /* validate_inputs holds m_cost to at least 8 blocks a lane, as KeePass does. */
    segment_length = context->m_cost / (context->lanes * ARGON2_SYNC_POINTS);
    memset(&instance, 0, sizeof instance);
    instance.version = context->version;
    instance.passes = context->t_cost;
    instance.memory_blocks = segment_length * context->lanes * ARGON2_SYNC_POINTS;
    instance.segment_length = segment_length;
    instance.lane_length = segment_length * ARGON2_SYNC_POINTS;
    instance.lanes = context->lanes;
    instance.threads = context->threads < context->lanes ? context->threads : context->lanes;
    instance.type = job->type;
    instance.given_up = &job->given_up;

    result = initialize(&instance, context);
    if (result != ARGON2_OK) return result; /* nothing allocated: initialize fails only at the allocation */
    fill(&instance);
    finalize(context, &instance); /* the memory wiped and freed; a key of a derivation given up is never handed out */
    return ARGON2_OK;
}

static void *derive(void *argument) {
    job_t *job = argument;
    int result = run(job);
    pthread_mutex_lock(&job->lock);
    job->result = result;
    job->done = 1;
    pthread_cond_broadcast(&job->ended);
    pthread_mutex_unlock(&job->lock);
    return NULL;
}

static uint8_t *copy_in(JNIEnv *env, jbyteArray array, jsize *length) {
    *length = (*env)->GetArrayLength(env, array);
    uint8_t *copy = malloc(*length > 0 ? (size_t)*length : 1);
    if (copy != NULL) (*env)->GetByteArrayRegion(env, array, 0, *length, (jbyte *)copy);
    return copy;
}

static void release(job_t *job) {
    argon2_context *context = &job->context;
    if (context->pwd != NULL) secure_wipe_memory(context->pwd, job->context.pwdlen);
    if (context->out != NULL) secure_wipe_memory(context->out, context->outlen);
    free(context->pwd);
    free(context->salt);
    free(context->out);
    free(job);
}

/* A derivation started, or 0 when it could not be (no memory for its buffers, no thread). */
JNIEXPORT jlong JNICALL Java_dev_keething_argon2_Bridge_start(
    JNIEnv *env, jclass class, jbyteArray password, jbyteArray salt, jint variant, jint iterations,
    jint memoryKiB, jint lanes, jint threads, jint length) {
    (void)class;
    job_t *job = calloc(1, sizeof(job_t));
    if (job == NULL) return 0;
    argon2_context *context = &job->context;
    jsize pwdlen, saltlen;
    context->pwd = copy_in(env, password, &pwdlen);
    context->salt = copy_in(env, salt, &saltlen);
    context->out = malloc((size_t)length);
    if (context->pwd == NULL || context->salt == NULL || context->out == NULL) {
        release(job);
        return 0;
    }
    context->pwdlen = (uint32_t)pwdlen;
    context->saltlen = (uint32_t)saltlen;
    context->outlen = (uint32_t)length;
    context->t_cost = (uint32_t)iterations; /* KeePass's 2³²−1 iterations, unsigned */
    context->m_cost = (uint32_t)memoryKiB;
    context->lanes = (uint32_t)lanes;
    context->threads = (uint32_t)threads;
    context->version = ARGON2_VERSION_13;
    context->flags = ARGON2_FLAG_CLEAR_PASSWORD;
    job->type = (argon2_type)variant; /* Argon2.Variant's ids are the reference code's */
    atomic_init(&job->given_up, 0);

    pthread_condattr_t clock;
    pthread_condattr_init(&clock);
    pthread_condattr_setclock(&clock, CLOCK_MONOTONIC);
    pthread_cond_init(&job->ended, &clock);
    pthread_condattr_destroy(&clock);
    pthread_mutex_init(&job->lock, NULL);
    if (pthread_create(&job->thread, NULL, derive, job) != 0) {
        pthread_cond_destroy(&job->ended);
        pthread_mutex_destroy(&job->lock);
        release(job);
        return 0;
    }
    return (jlong)(intptr_t)job;
}

/* Waits up to millis for the derivation to end; whether it has. */
JNIEXPORT jboolean JNICALL Java_dev_keething_argon2_Bridge_await(JNIEnv *env, jclass class, jlong handle, jint millis) {
    (void)env;
    (void)class;
    job_t *job = (job_t *)(intptr_t)handle;
    struct timespec until;
    clock_gettime(CLOCK_MONOTONIC, &until);
    until.tv_nsec += (long)millis * 1000000L;
    until.tv_sec += until.tv_nsec / 1000000000L;
    until.tv_nsec %= 1000000000L;
    pthread_mutex_lock(&job->lock);
    while (!job->done) {
        if (pthread_cond_timedwait(&job->ended, &job->lock, &until) != 0) break;
    }
    int done = job->done;
    pthread_mutex_unlock(&job->lock);
    return done ? JNI_TRUE : JNI_FALSE;
}

/* Gives the derivation up, from any thread: its lanes stop at their next check. */
JNIEXPORT void JNICALL Java_dev_keething_argon2_Bridge_stop(JNIEnv *env, jclass class, jlong handle) {
    (void)env;
    (void)class;
    atomic_store(&((job_t *)(intptr_t)handle)->given_up, 1);
}

/*
 * Waits for the derivation to end, writes its key into out when it finished (out holds the length asked for), and
 * wipes and frees it: ARGON2_OK, GIVEN_UP or the reference code's error. The handle is gone afterwards.
 */
JNIEXPORT jint JNICALL Java_dev_keething_argon2_Bridge_finish(JNIEnv *env, jclass class, jlong handle, jbyteArray out) {
    (void)class;
    job_t *job = (job_t *)(intptr_t)handle;
    pthread_join(job->thread, NULL);
    int result = job->result;
    if (result == ARGON2_OK && atomic_load(&job->given_up)) result = GIVEN_UP;
    if (result == ARGON2_OK) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)job->context.outlen, (const jbyte *)job->context.out);
    }
    pthread_cond_destroy(&job->ended);
    pthread_mutex_destroy(&job->lock);
    release(job);
    return result;
}
