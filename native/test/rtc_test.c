// The test of the WebRTC library, libdatachannel, as the build makes it
// (native/CMakeLists.txt): two peer connections in one process connect, open
// a data channel, and send messages both ways through its C API, rtc.h. On
// the host, libjuice also gathers loopback candidates, so the test needs no
// network; on Android, a network interface must be up. Each peer's
// description goes to the other only when its ICE gathering is complete, as
// the core sends a whole SDP through its tracker. Fails with a message and a
// non-zero exit, also when a step does not end in time.

#include <pthread.h>
#include <rtc/rtc.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define CHECK(condition)                                                                           \
  do {                                                                                             \
    if (!(condition)) {                                                                            \
      fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__, __LINE__, #condition);                \
      abort();                                                                                     \
    }                                                                                              \
  } while (0)

// The longest wait of one step.
// Five steps, with the certificates and rtcCleanup's own 10 s limit, fit in
// ctest's 90 s limit (native/CMakeLists.txt).
#define STEP_SECONDS 10
// The core's messages are up to 64 KiB - 1 bytes.
#define LARGE_BYTES (64 * 1024 - 1)
#define SDP_BYTES (16 * 1024)

enum { MAX_ECHOES = 8 };

// A peer connection, and its data channel once it has one. The library's
// threads set the fields.
typedef struct {
  int pc;
  atomic_int channel;
  atomic_bool gathered;
  atomic_bool open;
} peer;

// The messages that the offerer got back, in order: a copy of each, and its
// size (negative for a string, as rtc.h gives it). rtc.h does not say that
// one channel's callbacks never run at the same time, so a lock.
typedef struct {
  pthread_mutex_t lock;
  int count;
  char *data[MAX_ECHOES];
  int size[MAX_ECHOES];
} received;

static received echoes = {.lock = PTHREAD_MUTEX_INITIALIZER};

static int echo_count(void) {
  pthread_mutex_lock(&echoes.lock);
  int count = echoes.count;
  pthread_mutex_unlock(&echoes.lock);
  return count;
}

// rtcCleanup returns nothing: when it times out (a deadlock, or an object
// that is not freed), libdatachannel only logs it. The test fails then. Other
// error lines, such as one in a race of the closes, do not fail it.
static atomic_int cleanup_timeouts;

static void RTC_API log_line(rtcLogLevel level, const char *message) {
  if (level <= RTC_LOG_ERROR && strstr(message, "Cleanup timeout") != NULL) {
    atomic_fetch_add(&cleanup_timeouts, 1);
  }
  fprintf(stderr, "libdatachannel: %s\n", message);
}

static void RTC_API on_gathering(int pc, rtcGatheringState state, void *data) {
  (void)pc;
  peer *self = data;
  if (state == RTC_GATHERING_COMPLETE) {
    atomic_store(&self->gathered, true);
  }
}

static void RTC_API on_open(int channel, void *data) {
  (void)channel;
  peer *self = data;
  atomic_store(&self->open, true);
}

// The answerer echoes each message, of the same kind.
static void RTC_API on_message_echo(int channel, const char *message, int size, void *data) {
  (void)data;
  CHECK(rtcSendMessage(channel, message, size) >= 0);
}

static void RTC_API on_message_back(int channel, const char *message, int size, void *data) {
  (void)channel;
  (void)data;
  // A string's size is negative; its bytes end with a NUL.
  size_t length = size < 0 ? strlen(message) + 1 : (size_t)size;
  char *copy = malloc(length + 1);
  CHECK(copy != NULL);
  // An empty binary message has no data pointer.
  if (length > 0) {
    memcpy(copy, message, length);
  }
  pthread_mutex_lock(&echoes.lock);
  CHECK(echoes.count < MAX_ECHOES);
  echoes.data[echoes.count] = copy;
  echoes.size[echoes.count] = size;
  echoes.count++;
  pthread_mutex_unlock(&echoes.lock);
}

static void RTC_API on_channel(int pc, int channel, void *data) {
  (void)pc;
  peer *self = data;
  CHECK(rtcSetMessageCallback(channel, on_message_echo) == RTC_ERR_SUCCESS);
  atomic_store(&self->channel, channel);
  // The answerer's channel is open when it arrives.
  atomic_store(&self->open, true);
}

static double now_seconds(void) {
  struct timespec time;
  clock_gettime(CLOCK_MONOTONIC, &time);
  return (double)time.tv_sec + (double)time.tv_nsec / 1e9;
}

// Waits until `condition` is true; fails the test after STEP_SECONDS.
#define WAIT_UNTIL(condition, what)                                                                \
  do {                                                                                             \
    double end = now_seconds() + STEP_SECONDS;                                                     \
    while (!(condition)) {                                                                         \
      if (now_seconds() > end) {                                                                   \
        fprintf(stderr, "rtc_test: no %s in %d s\n", what, STEP_SECONDS);                          \
        abort();                                                                                   \
      }                                                                                            \
      nanosleep(&(struct timespec){.tv_nsec = 10L * 1000 * 1000}, NULL);                           \
    }                                                                                              \
  } while (0)

static void make_peer(peer *self) {
  rtcConfiguration config = {0};
  self->pc = rtcCreatePeerConnection(&config);
  CHECK(self->pc > 0);
  // The library gives this pointer also to each data channel of the peer
  // connection, and to its callbacks.
  rtcSetUserPointer(self->pc, self);
  CHECK(rtcSetGatheringStateChangeCallback(self->pc, on_gathering) == RTC_ERR_SUCCESS);
}

// Gives `from`'s whole local description to `to`, once `from` gathered.
static void signal_description(peer *from, peer *to, const char *what) {
  WAIT_UNTIL(atomic_load(&from->gathered), what);
  static char sdp[SDP_BYTES];
  static char type[16];
  CHECK(rtcGetLocalDescription(from->pc, sdp, sizeof sdp) > 0);
  CHECK(rtcGetLocalDescriptionType(from->pc, type, sizeof type) > 0);
  CHECK(rtcSetRemoteDescription(to->pc, sdp, type) == RTC_ERR_SUCCESS);
}

int main(void) {
  rtcInitLogger(RTC_LOG_WARNING, log_line);
  peer offerer = {0};
  peer answerer = {0};
  make_peer(&offerer);
  make_peer(&answerer);
  CHECK(rtcSetDataChannelCallback(answerer.pc, on_channel) == RTC_ERR_SUCCESS);

  // The channel starts the offer.
  int channel = rtcCreateDataChannel(offerer.pc, "test");
  CHECK(channel > 0);
  CHECK(rtcSetOpenCallback(channel, on_open) == RTC_ERR_SUCCESS);
  CHECK(rtcSetMessageCallback(channel, on_message_back) == RTC_ERR_SUCCESS);
  signal_description(&offerer, &answerer, "offer");
  signal_description(&answerer, &offerer, "answer");
  WAIT_UNTIL(atomic_load(&offerer.open), "open data channel at the offerer");
  WAIT_UNTIL(atomic_load(&answerer.open), "data channel at the answerer");

  static char large[LARGE_BYTES];
  for (int i = 0; i < LARGE_BYTES; i++) {
    large[i] = (char)(i * 7);
  }
  const char binary[] = {0, 1, 2, (char)255};
  const struct {
    const char *data;
    int size;
  } sent[] = {
      {"h\xc3\xa9llo", -1},
      {binary, (int)sizeof binary},
      {"", 0},
      {large, LARGE_BYTES},
  };
  const int count = (int)(sizeof sent / sizeof sent[0]);
  for (int i = 0; i < count; i++) {
    CHECK(rtcSendMessage(channel, sent[i].data, sent[i].size) >= 0);
  }
  WAIT_UNTIL(echo_count() == count, "echo of each message");
  for (int i = 0; i < count; i++) {
    if (sent[i].size < 0) {
      CHECK(echoes.size[i] < 0);
      CHECK(strcmp(echoes.data[i], sent[i].data) == 0);
    } else {
      CHECK(echoes.size[i] == sent[i].size);
      CHECK(memcmp(echoes.data[i], sent[i].data, (size_t)sent[i].size) == 0);
    }
    free(echoes.data[i]);
  }

  // The teardown that the engine uses: each channel, then each peer
  // connection.
  CHECK(rtcClose(channel) == RTC_ERR_SUCCESS);
  CHECK(rtcDeleteDataChannel(channel) == RTC_ERR_SUCCESS);
  CHECK(rtcDeleteDataChannel(atomic_load(&answerer.channel)) == RTC_ERR_SUCCESS);
  CHECK(rtcDeletePeerConnection(offerer.pc) == RTC_ERR_SUCCESS);
  CHECK(rtcDeletePeerConnection(answerer.pc) == RTC_ERR_SUCCESS);
  rtcCleanup();
  CHECK(atomic_load(&cleanup_timeouts) == 0);
  printf("rtc_test: %d messages each way\n", count);
  return 0;
}
