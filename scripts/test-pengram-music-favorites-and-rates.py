#!/usr/bin/env python3
"""Static regression checks for favorites, chat music UX and TON display rates."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


def check_favorites_lifecycle():
    favorites = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramMusicFavorites.java")
    settings = read("TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")
    config = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java")

    assert "650L" in favorites and "PENDING_LIKE_DELIVERIES" in favorites
    assert "SAVE_IN_FLIGHT_UNTIL" in favorites
    assert "TL_inputMessagesFilterMusic" in favorites and "request.limit = 100" in favorites
    assert "isSameTrack(account, message, wantedPrimary, wantedMeta)" in favorites
    assert "findSavedCopies(account, track, targetDialogId, false" in favorites
    assert "findSavedCopies(account, track, targetDialogId, true" in favorites
    assert "excludeOriginalMessage && targetDialogId == track.getDialogId()" in favorites
    assert "deleteMessages(ids, null, null, targetDialogId, 0, true, 0)" in favorites
    assert "saveTargetDialogId(account, primaryKey(track), metaFingerprint(track), targetDialogId)" in favorites
    assert "saveTargetTimestamp(account, primaryKey(track), metaFingerprint(track), sentAt)" in favorites
    assert "getUnlikeTargetCandidates(account, savedTarget)" in favorites
    assert "message.date >= earliestSavedDate" in favorites

    keys = (
        "KEY_PLAYER_FAV_SHOW_FULL_PLAYER",
        "KEY_PLAYER_FAV_SHOW_MINI_PLAYER",
        "KEY_PLAYER_FAV_SHOW_CHAT_LIST",
        "KEY_PLAYER_FAV_SHOW_NOTIFICATION",
    )
    for key in keys:
        assert key in config
        assert key in settings
    assert "notifyServiceChanged()" in settings
    assert "messagePlayingPlayStateChanged, playing != null ? playing.getId() : 0" in settings
    service = read("TMessagesProj/src/main/java/org/telegram/messenger/MusicPlayerService.java")
    receiver = read("TMessagesProj/src/main/java/org/telegram/messenger/MusicPlayerReceiver.java")
    assert "isPlayerFavoritesEnabled() && PengramConfig.isPlayerFavShowNotification()" in service
    assert "isPlayerFavoritesEnabled() && PengramConfig.isPlayerFavShowNotification()" in receiver


def check_chat_music_ux_and_live_favorites():
    sheet = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramChatMusicSheet.java")
    assert "favoritesChangedListener" in sheet
    assert "PengramMusicFavorites.addListener(favoritesChangedListener)" in sheet
    assert "PengramMusicFavorites.removeListener(favoritesChangedListener)" in sheet
    assert "sortMode == 0" in sheet and "sortMode == 1" in sheet
    assert "sortText(a, sortMode).compareToIgnoreCase" in sheet
    assert "getVisibleTracks()" in sheet and "matches(track)" in sheet
    assert "MediaController.getInstance().setPlaylist(playlist, playlist.get(0), 0, false, null)" in sheet
    assert "Collections.shuffle(playlist)" in sheet
    assert "isPlayerFavShowChatList()" in sheet


def check_live_ton_display_rate():
    rates = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramCurrencyRates.java")
    wallet = read("TMessagesProj/src/main/java/org/telegram/ui/Wallet/WalletSendEnterAmount.java")
    for provider in (
        "api.coingecko.com/api/v3/simple/price",
        "api.coinpaprika.com/v1/tickers/ton-toncoin",
        "api.coincap.io/v2/assets/toncoin",
        "api.binance.com/api/v3/ticker/price",
    ):
        assert provider in rates, f"Missing TON market provider: {provider}"
    assert "getFreshTonRate" in rates
    assert "tonMarketUpdatedMs" in rates and "tonFiatUpdatedMs" in rates
    assert "getFreshTonRate(wallet.currencies.getCurrency())" in wallet
    assert "currencyPerGram" in wallet
    assert "formatCurrencyInput" in wallet
    # The market quote is read by the display conversion helper, not written into a transaction request.
    conversion = wallet[wallet.index("private BigDecimal getCurrencyPerGram()"):wallet.index("private BigDecimal getCurrencyPerGram()") + 1800]
    assert "getFreshTonRate" in conversion and "return BigDecimal.valueOf(liveMarketRate)" in conversion


def check_strings():
    for language, values_dir in (("en", "values"), ("ru", "values-ru")):
        path = ROOT / f"TMessagesProj/src/main/res/{values_dir}/strings.xml"
        root = ET.parse(path).getroot()
        names = {node.attrib.get("name") for node in root.findall("string")}
        for key in (
            "PengramChatMusicSearchHint",
            "PengramChatMusicSortRecent",
            "PengramChatMusicSortOldest",
            "PengramChatMusicSortTitle",
            "PengramChatMusicSortArtist",
            "PengramPlayerFavShowFullPlayer",
            "PengramPlayerFavShowFullPlayerInfo",
            "PengramPlayerFavShowMiniPlayer",
            "PengramPlayerFavShowMiniPlayerInfo",
            "PengramPlayerFavShowChatList",
            "PengramPlayerFavShowChatListInfo",
            "PengramPlayerFavShowNotification",
            "PengramPlayerFavShowNotificationInfo",
        ):
            assert key in names, f"Missing {language} string {key}"


if __name__ == "__main__":
    check_favorites_lifecycle()
    check_chat_music_ux_and_live_favorites()
    check_live_ton_display_rate()
    check_strings()
    print("Pengram music favorites/rates regression checks passed")
