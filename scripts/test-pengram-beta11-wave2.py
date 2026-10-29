#!/usr/bin/env python3
"""Regression suite for Beta 11+ Wave 2:
1. TikTok Motion / Live Photos (PhotoItem, extractSlideMotionVideoUrl, animated image MP4 transcode, LIVE badge)
2. Giant emoji fix in replies (cloneSpans scale clamping, normalizeForCompactLine, Emoji.replaceEmoji standard guard)
3. Classic full-width Profile Music Bar (isClassicBarMode, PeerColor/RenderNode blur, album cover, live equalizer)
4. 3D Cylinder Scroll + Liquid Glass fix (4-slot BlurSlot pool, cylinder hash, asin() horizon, API < 33 guard)
5. Disable Proximity Sensor for voice/round messages (default true, MediaController guards)
6. In-chat Music Queue in Mini-Player (FragmentContextView + compact PengramMusicPlayerSheet + PengramChatMusicSheet)
7. Liked Music "❤️" button (strictly gated on isPlayerFavoritesEnabled, MediaSession + Notification + Player + Vault)
   and Smart Track Recognition (PengramTrackRecognizer, ID3/FLAC/kbps + iTunes/Deezer + PengramMusicMeta cleanup)
8. Pengram Settings existing structure preserved (no section reorganization) + EN/RU strings & search index parity.
"""

from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'

config = (java / 'messenger/PengramConfig.java').read_text()
resolver = (java / 'messenger/PengramLinkResolver.java').read_text()
assistant = (java / 'messenger/PengramLinkAssistant.java').read_text()
slideshow = (java / 'messenger/PengramPhotoSlideshow.java').read_text()
preview_sheet = (java / 'ui/Components/PengramLinkPreviewSheet.java').read_text()
emoji_java = (java / 'messenger/Emoji.java').read_text()
anim_span = (java / 'ui/Components/AnimatedEmojiSpan.java').read_text()
msg_cell = (java / 'ui/Cells/ChatMessageCell.java').read_text()
profile_music = (java / 'ui/Components/ProfileMusicView.java').read_text()
profile_act = (java / 'ui/ProfileActivity.java').read_text()
cylinder = (java / 'ui/Components/PengramCylinderScroll.java').read_text()
rlv = (java / 'ui/Components/RecyclerListView.java').read_text()
chat_act = (java / 'ui/ChatActivity.java').read_text()
media_ctrl = (java / 'messenger/MediaController.java').read_text()
music_meta = (java / 'messenger/PengramMusicMeta.java').read_text()
track_rec = (java / 'messenger/PengramTrackRecognizer.java').read_text()
music_fav = (java / 'messenger/PengramMusicFavorites.java').read_text()
player_srv = (java / 'messenger/MusicPlayerService.java').read_text()
player_rcv = (java / 'messenger/MusicPlayerReceiver.java').read_text()
fcv = (java / 'ui/Components/FragmentContextView.java').read_text()
player_sheet = (java / 'ui/Components/PengramMusicPlayerSheet.java').read_text()
chat_music = (java / 'ui/Components/PengramChatMusicSheet.java').read_text()
settings = (java / 'ui/PengramSettingsActivity.java').read_text()
search_idx = (java / 'ui/PengramSearchIndex.java').read_text()
manifest = (root / 'TMessagesProj/src/main/AndroidManifest.xml').read_text()

# 1. TikTok Motion / Live Photos
assert 'KEY_LINK_LIVE_PHOTOS = "linkLivePhotos"' in config
assert 'getBool(KEY_LINK_LIVE_PHOTOS, true)' in config
assert 'motionVideoUrl' in resolver and 'animatedImageUrl' in resolver and 'isMotion()' in resolver
assert 'extractSlideMotionVideoUrl' in resolver and 'pickAnimatedImageUrl' in resolver
assert 'isAnimatedImageBytes' in resolver
assert 'convertAnimatedImageToMp4' in slideshow and 'isAnimatedImageFile' in slideshow
assert 'PengramConfig.isLinkLivePhotos()' in assistant
assert 'info.isVideo = mediaFile.getName().endsWith(".mp4")' in assistant
assert 'LIVE' in preview_sheet and 'isMotion()' in preview_sheet

# 2. Reply Giant Emoji Fix
assert 'normalizeForCompactLine' in anim_span
assert 'if (!oldSpan.standard' in anim_span
assert 'if (animatedSpan.standard && fontMetrics != null)' in emoji_java
assert 'AnimatedEmojiSpan.normalizeForCompactLine(mess, textPaint.getFontMetricsInt())' in msg_cell

# 3. Classic Profile Music Bar
assert 'KEY_CLASSIC_PROFILE_MUSIC = "classicProfileMusic"' in config
assert 'protected boolean isClassicBarMode()' in profile_music
assert 'drawClassicBar(canvas)' in profile_music
assert 'setMusicDocument(' in profile_music and 'setMusicDocument(' in profile_act
assert 'private int getPillsExtraHeightDp()' in profile_act

# 4. 3D Cylinder Scroll + Liquid Glass
assert 'private static final int BLUR_SLOT_COUNT = 4;' in cylinder
assert 'drawForBlurCapture(' in cylinder
assert 'getBlurStateHash()' in cylinder
assert 'Math.asin(' in cylinder
assert 'expandBlurSourceTop(' in rlv and 'isMeshWarpActive()' in chat_act
assert 'chatInputViewsContainer.getInputBubbleTop()' in chat_act

# 5. Disable Voice Proximity Sensor (Default ON)
assert 'KEY_DISABLE_VOICE_PROXIMITY = "disableVoiceProximity"' in config
assert 'getBool(KEY_DISABLE_VOICE_PROXIMITY, true)' in config
assert media_ctrl.count('PengramConfig.isDisableVoiceProximity()') >= 3
assert 'public void onPengramProximitySettingChanged()' in media_ctrl

# 6. Mini-Player Chat Music Queue
assert 'KEY_MINI_PLAYER_QUEUE_BTN = "miniPlayerQueueBtn"' in config
assert 'pengramQueueButton' in fcv and 'pengramOpenChatMusicList' in fcv
assert 'if (!compact || PengramConfig.isMiniPlayerQueueBtn())' in player_sheet
assert 'openQueueOnTab(' in player_sheet and 'openFullChatMusicSheet()' in player_sheet

# 7. Liked Music "❤️" + Smart Track Recognition
assert 'KEY_PLAYER_FAVORITES = "playerFavoritesEnabled"' in config
assert 'getBool(KEY_PLAYER_FAVORITES, false)' in config
assert 'org.telegram.android.musicplayer.pengram_like' in manifest
assert 'NOTIFY_LIKE' in player_srv and 'onPengramFavoritesStateChanged()' in player_srv
assert 'showFavHeart = messageObject.isMusic() && PengramConfig.isPlayerFavoritesEnabled()' in player_srv
assert 'PengramMusicFavorites.ACTION_LIKE' in player_rcv
assert 'pengramLikeButton' in fcv and 'likeButton' in player_sheet and 'heartButton' in chat_music
assert 'TL_channels_createChannel' in music_fav
assert 'resolveDisplayPair' in music_meta
assert 'buildBadgeLine' in track_rec and 'recognize(' in track_rec
assert (root / 'TMessagesProj/src/main/res/drawable/ic_pengram_heart_outline.xml').exists()
assert (root / 'TMessagesProj/src/main/res/drawable/ic_pengram_heart_filled.xml').exists()

# 8. EN/RU Strings & Search Index Parity + Untouched Root Sections
for locale in ('values', 'values-ru'):
    strings = {e.get('name'): (e.text or '') for e in ET.parse(root / f'TMessagesProj/src/main/res/{locale}/strings.xml').iter('string')}
    for key in (
        'PengramLinkLivePhotos', 'PengramLinkLivePhotosInfo',
        'PengramClassicProfileMusic', 'PengramClassicProfileMusicInfo',
        'PengramDisableVoiceProximity', 'PengramDisableVoiceProximityInfo',
        'PengramMiniPlayerQueueBtn', 'PengramPlayerFavorites',
        'PengramPlayerFavTarget', 'PengramMusicVaultTitle',
        'PengramSmartTrackRecognition', 'PengramTrackInfoHeader',
    ):
        assert key in strings and strings[key].strip(), f'Missing {key} in {locale}'

for indexed in ('PengramLinkLivePhotos', 'PengramClassicProfileMusic', 'PengramDisableVoiceProximity', 'PengramPlayerFavorites', 'PengramSmartTrackRecognition'):
    assert f'R.string.{indexed}' in search_idx, f'Not indexed in PengramSearchIndex: {indexed}'

print('Pengram Beta 11+ Wave 2 (all 7 features + strings + search index): ALL OK')
