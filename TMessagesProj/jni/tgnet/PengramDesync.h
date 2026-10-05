/*
 * Pengram: десинхронизация первых пакетов соединения.
 *
 * Российские ТСПУ и большинство DPI ищут сигнатуру в ПЕРВОМ сегменте TCP:
 * у Telegram это 64 байта obfuscated2-инициализации, а при FakeTLS — TLS
 * ClientHello. Если тот же самый байтовый поток уехать несколькими сегментами
 * (и/или с паузами), склеить и опознать его заметно сложнее, а сервер получает
 * ровно то же самое — для него это обычный TCP.
 *
 * Здесь нет ничего, что требует root или VpnService: только send() и setsockopt()
 * на нашем же сокете. Режим «фейковый сегмент» требует TCP_REPAIR (а значит
 * CAP_NET_ADMIN) и сам отключается, если прав нет.
 */

#ifndef PENGRAMDESYNC_H
#define PENGRAMDESYNC_H

#include <cstdint>
#include <cstddef>
#include <sys/types.h>

namespace PengramDesync {

    struct Params {
        bool enabled = false;
        /** к скольким первым отправкам соединения применять стратегию */
        int32_t firstPackets = 1;
        /** позиции разрезов, 0 — не резать; отрицательное — отступ с конца */
        int32_t split1 = 0;
        int32_t split2 = 0;
        int32_t split3 = 0;
        /** гулять позициями разреза ±3 байта, чтобы не было одинакового профиля */
        bool randomSplit = false;
        /** пауза между кусками, мс (0 — без паузы) */
        int32_t delayMs = 0;
        /** байт срочных данных (TCP urgent) между кусками — ломает сборку потока */
        bool oob = false;
        int32_t oobChar = 0x61;
        /** отключить алгоритм Нейгла, чтобы куски реально ушли отдельными сегментами */
        bool noDelay = true;
        /** фейковый сегмент с коротким TTL до настоящих данных (нужен TCP_REPAIR) */
        bool fake = false;
        int32_t fakeTtl = 3;
    };

    void set(const Params &params);
    Params get();
    bool isEnabled();

    /** 0 — ещё не пробовали, 1 — получилось, -1 — нет прав (нужен root) */
    int fakeSupport();

    /**
     * Отправляет первую порцию данных по выбранной стратегии.
     * Возвращает то же, что send(): сколько байт принято ядром, или -1.
     */
    ssize_t sendDesynced(int fd, const uint8_t *data, size_t size, bool ipv6);
}

#endif
