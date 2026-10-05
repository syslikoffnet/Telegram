#include "PengramDesync.h"

#include <cstring>
#include <cstdlib>
#include <mutex>
#include <ctime>
#include <errno.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>

#ifndef TCP_REPAIR
#define TCP_REPAIR 19
#endif
#ifndef TCP_REPAIR_QUEUE
#define TCP_REPAIR_QUEUE 20
#endif
#ifndef TCP_QUEUE_SEQ
#define TCP_QUEUE_SEQ 21
#endif
#ifndef TCP_SEND_QUEUE
#define TCP_SEND_QUEUE 2
#endif

namespace PengramDesync {

    static std::mutex paramsMutex;
    static Params currentParams;
    static int fakeSupported = 0;

    void set(const Params &params) {
        std::lock_guard<std::mutex> lock(paramsMutex);
        currentParams = params;
        if (currentParams.firstPackets < 1) {
            currentParams.firstPackets = 1;
        } else if (currentParams.firstPackets > 8) {
            currentParams.firstPackets = 8;
        }
        if (currentParams.delayMs < 0) {
            currentParams.delayMs = 0;
        } else if (currentParams.delayMs > 100) {
            currentParams.delayMs = 100;   // пауза на сетевом потоке, больше нельзя
        }
        if (currentParams.fakeTtl < 1) {
            currentParams.fakeTtl = 1;
        } else if (currentParams.fakeTtl > 16) {
            currentParams.fakeTtl = 16;
        }
    }

    Params get() {
        std::lock_guard<std::mutex> lock(paramsMutex);
        return currentParams;
    }

    bool isEnabled() {
        std::lock_guard<std::mutex> lock(paramsMutex);
        return currentParams.enabled;
    }

    int fakeSupport() {
        std::lock_guard<std::mutex> lock(paramsMutex);
        return fakeSupported;
    }

    /** позиция разреза: отрицательная считается с конца, 0 и выходящие за край — игнорируются */
    static size_t normalizePos(int32_t pos, size_t size, bool randomize) {
        if (pos == 0) {
            return 0;
        }
        long value = pos > 0 ? pos : (long) size + pos;
        if (randomize) {
            value += (random() % 7) - 3;
        }
        if (value <= 0 || (size_t) value >= size) {
            return 0;
        }
        return (size_t) value;
    }

    static void sleepMs(int32_t ms) {
        if (ms <= 0) {
            return;
        }
        struct timespec ts;
        ts.tv_sec = ms / 1000;
        ts.tv_nsec = (long) (ms % 1000) * 1000000L;
        nanosleep(&ts, nullptr);
    }

    /**
     * Фейковый сегмент: отправляем мусор с TTL, которого хватит до DPI, но не до
     * сервера, и откатываем номер последовательности назад — сервер ничего не
     * заметит, а DPI увидит «не тот» поток. Работает только при наличии
     * TCP_REPAIR (CAP_NET_ADMIN), иначе тихо отключается навсегда.
     */
    static bool sendFake(int fd, const uint8_t *data, size_t size, int32_t ttl, bool ipv6) {
        if (fakeSupported < 0 || size == 0) {
            return false;
        }
        int on = 1;
        if (setsockopt(fd, IPPROTO_TCP, TCP_REPAIR, &on, sizeof(on)) != 0) {
            std::lock_guard<std::mutex> lock(paramsMutex);
            fakeSupported = -1;
            return false;
        }
        int queue = TCP_SEND_QUEUE;
        uint32_t seq = 0;
        socklen_t seqLen = sizeof(seq);
        bool ok = setsockopt(fd, IPPROTO_TCP, TCP_REPAIR_QUEUE, &queue, sizeof(queue)) == 0
                  && getsockopt(fd, IPPROTO_TCP, TCP_QUEUE_SEQ, &seq, &seqLen) == 0;
        int off = 0;
        if (!ok) {
            setsockopt(fd, IPPROTO_TCP, TCP_REPAIR, &off, sizeof(off));
            std::lock_guard<std::mutex> lock(paramsMutex);
            fakeSupported = -1;
            return false;
        }
        setsockopt(fd, IPPROTO_TCP, TCP_REPAIR, &off, sizeof(off));

        int savedTtl = 0;
        socklen_t ttlLen = sizeof(savedTtl);
        const int level = ipv6 ? IPPROTO_IPV6 : IPPROTO_IP;
        const int option = ipv6 ? IPV6_UNICAST_HOPS : IP_TTL;
        getsockopt(fd, level, option, &savedTtl, &ttlLen);
        int fakeTtl = ttl;
        setsockopt(fd, level, option, &fakeTtl, sizeof(fakeTtl));

        uint8_t junk[64];
        const size_t junkSize = size < sizeof(junk) ? size : sizeof(junk);
        for (size_t a = 0; a < junkSize; ++a) {
            junk[a] = (uint8_t) (random() & 0xff);
        }
        send(fd, junk, junkSize, MSG_NOSIGNAL);

        if (savedTtl > 0) {
            setsockopt(fd, level, option, &savedTtl, sizeof(savedTtl));
        }
        // откатываем номер последовательности на место: настоящие данные уйдут туда же,
        // куда ушёл фейк, и сервер увидит только их
        setsockopt(fd, IPPROTO_TCP, TCP_REPAIR, &on, sizeof(on));
        setsockopt(fd, IPPROTO_TCP, TCP_REPAIR_QUEUE, &queue, sizeof(queue));
        setsockopt(fd, IPPROTO_TCP, TCP_QUEUE_SEQ, &seq, sizeof(seq));
        setsockopt(fd, IPPROTO_TCP, TCP_REPAIR, &off, sizeof(off));
        {
            std::lock_guard<std::mutex> lock(paramsMutex);
            fakeSupported = 1;
        }
        return true;
    }

    ssize_t sendDesynced(int fd, const uint8_t *data, size_t size, bool ipv6) {
        const Params params = get();
        if (!params.enabled || size < 4) {
            return send(fd, data, size, 0);
        }

        if (params.noDelay) {
            int on = 1;
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &on, sizeof(on));
        }
        if (params.fake) {
            sendFake(fd, data, size, params.fakeTtl, ipv6);
        }

        // собираем границы кусков
        size_t cuts[3];
        int cutCount = 0;
        const int32_t raw[3] = {params.split1, params.split2, params.split3};
        for (int a = 0; a < 3; ++a) {
            const size_t pos = normalizePos(raw[a], size, params.randomSplit);
            if (pos == 0) {
                continue;
            }
            bool duplicate = false;
            for (int b = 0; b < cutCount; ++b) {
                if (cuts[b] == pos) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                cuts[cutCount++] = pos;
            }
        }
        // по возрастанию (их максимум три)
        for (int a = 0; a < cutCount; ++a) {
            for (int b = a + 1; b < cutCount; ++b) {
                if (cuts[b] < cuts[a]) {
                    const size_t tmp = cuts[a];
                    cuts[a] = cuts[b];
                    cuts[b] = tmp;
                }
            }
        }
        if (cutCount == 0) {
            return send(fd, data, size, 0);
        }

        size_t offset = 0;
        for (int a = 0; a <= cutCount; ++a) {
            const size_t end = a < cutCount ? cuts[a] : size;
            if (end <= offset) {
                continue;
            }
            const size_t want = end - offset;
            const ssize_t sent = send(fd, data + offset, want, 0);
            if (sent < 0) {
                // сокет неблокирующий: если часть уже ушла, честно вернём её,
                // остальное уедет следующим вызовом
                return offset > 0 ? (ssize_t) offset : -1;
            }
            offset += (size_t) sent;
            if ((size_t) sent < want) {
                // ядро приняло меньше, чем просили — дальше резать нечего
                return (ssize_t) offset;
            }
            if (a < cutCount) {
                if (params.oob) {
                    const uint8_t urgent = (uint8_t) params.oobChar;
                    send(fd, &urgent, 1, MSG_OOB);
                }
                sleepMs(params.delayMs);
            }
        }
        return (ssize_t) offset;
    }
}
