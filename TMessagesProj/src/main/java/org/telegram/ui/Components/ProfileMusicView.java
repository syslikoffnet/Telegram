package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.ActionBar.Theme.multAlpha;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramMusicMeta;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ProfileActivity;

public class ProfileMusicView extends View {

    private final Theme.ResourcesProvider resourcesProvider;
    private final PorterDuffColorFilter filterColorWhite = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
    private final PorterDuffColorFilter filterColorBlack = new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);

    private Text author, title;
    private Text classicAuthor, classicTitle;
    private CharSequence rawAuthorText = "";
    private CharSequence rawTitleText = "";
    private TLRPC.Document currentDocument;
    private final ImageReceiver coverReceiver = new ImageReceiver(this);
    private boolean hasCoverThumb;
    private boolean lastMeasuredClassic;

    private ProfileMusicView trailingView;
    private ProfileMusicView leadingView;

    // The two pills share a centered row in compact mode; in classic full-width mode they stack cleanly.
    public void setTrailingView(ProfileMusicView view) {
        trailingView = view;
        view.leadingView = this;
        invalidate();
        view.invalidate();
    }

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint classicBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint classicStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();
    private final Drawable icon;

    private final RectF rect = new RectF();
    private final RectF badgeRect = new RectF();

    public void getButtonBoundsOnScreen(RectF bounds) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        bounds.set(rect);
        bounds.offset(location[0], location[1]);
    }

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final Path clipAvatarPath = new Path();

    private final ButtonBounce bounce = new ButtonBounce(this);

    public ProfileMusicView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        icon = context.getResources().getDrawable(R.drawable.files_music).mutate();
        coverReceiver.setRoundRadius(dp(6));

        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
        arrowPath.moveTo(0, -dpf2(3.33f));
        arrowPath.lineTo(dpf2(3.16f), 0);
        arrowPath.lineTo(0, dpf2(3.33f));

        setColor(null);
        setText("Author", " - Title");
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        coverReceiver.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        coverReceiver.onDetachedFromWindow();
    }

    protected boolean isClassicBarMode() {
        return leadingView == null && !(this instanceof ProfileBalanceView) && PengramConfig.isClassicProfileMusic();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final boolean classic = isClassicBarMode();
        lastMeasuredClassic = classic;
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.EXACTLY
            ),
            MeasureSpec.makeMeasureSpec(
                dp(classic ? 48 : 37),
                MeasureSpec.EXACTLY
            )
        );
    }

    protected int textColor = Color.WHITE;
    protected boolean hasProfileColor;
    private float parentExpanded;
    private int buttonColor;
    private boolean hasColorById;
    private int classicBackgroundColor;
    private boolean classicWithShadows;
    private MessagesController.PeerColor lastPeerColor;
    private RadialGradient radialGradient;
    private float radialGradientWidth;
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    public void setColor(MessagesController.PeerColor peerColor) {
        lastPeerColor = peerColor;
        hasProfileColor = peerColor != null;
        int color1, color2;
        if (peerColor == null) {
            color1 = color2 = Theme.getColor(Theme.key_actionBarDefault, resourcesProvider);
            classicBackgroundColor = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
            classicWithShadows = true;
        } else {
            color1 = peerColor.getBgColor1(Theme.isCurrentThemeDark());
            color2 = peerColor.getBgColor2(Theme.isCurrentThemeDark());
            classicBackgroundColor = Theme.adaptHSV(ColorUtils.blendARGB(color1, color2, .15f), +.04f, -.09f);
            classicWithShadows = false;
        }
        classicBgPaint.setColor(classicBackgroundColor);
        if (trailingView != null) trailingView.setColor(peerColor);
        checkTextColor();
        invalidate();
    }

    // Same color as ProfileActionsView buttons.
    public void setButtonColor(int color, boolean hasColorById) {
        if (trailingView != null) trailingView.setButtonColor(color, hasColorById);
        if (buttonColor == color && this.hasColorById == hasColorById) return;
        buttonColor = color;
        this.hasColorById = hasColorById;
        radialGradient = null;
        backgroundPaint.setColor(color);
        checkTextColor();
    }

    private boolean isButtonColorLight() {
        return AndroidUtilities.computePerceivedBrightness(buttonColor) > 0.72f;
    }

    private boolean isClassicColorLight() {
        return AndroidUtilities.computePerceivedBrightness(classicBackgroundColor) > 0.80f;
    }

    private void checkTextColor() {
        final boolean lightBg = isClassicBarMode() ? isClassicColorLight() : isButtonColorLight();
        final boolean useBlackText = parentExpanded < 0.8f && lightBg;
        textColor = useBlackText ? Color.BLACK : Color.WHITE;
        icon.setColorFilter(useBlackText ? filterColorBlack : filterColorWhite);
        iconPaint.setColor(textColor);
        arrowPaint.setColor(Theme.multAlpha(textColor, 0.85f));
        invalidate();
    }

    public void setParentExpanded(float expanded) {
        if (trailingView != null) trailingView.setParentExpanded(expanded);
        if (parentExpanded != expanded) {
            parentExpanded = expanded;
            checkTextColor();
            invalidate();
        }
    }

    public void setMusicDocument(TLRPC.Document document) {
        currentDocument = document;
        hasCoverThumb = false;
        if (document != null && document.thumbs != null && !document.thumbs.isEmpty()) {
            final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90);
            if (thumb != null) {
                coverReceiver.setImage(ImageLocation.getForDocument(thumb, document), "48_48", null, null, document, 0);
                hasCoverThumb = true;
            } else {
                coverReceiver.clearImage();
            }
        } else {
            coverReceiver.clearImage();
        }

        CharSequence rawAuthor = getAuthor(document);
        CharSequence rawTitle = getTitle(document);
        String cleanAuthor = rawAuthor != null ? PengramMusicMeta.cleanPerformerTag(rawAuthor.toString()) : null;
        String cleanTitle = rawTitle != null ? PengramMusicMeta.cleanTitleTag(rawTitle.toString()) : null;
        if (TextUtils.isEmpty(cleanAuthor) && !TextUtils.isEmpty(cleanTitle)) {
            final String[] split = PengramMusicMeta.splitAny(cleanTitle);
            if (split != null) {
                cleanAuthor = split[0];
                cleanTitle = split[1];
            }
        }
        CharSequence authorCs = !TextUtils.isEmpty(cleanAuthor) ? cleanAuthor : rawAuthor;
        CharSequence titleCs = !TextUtils.isEmpty(cleanTitle) ? cleanTitle : rawTitle;

        if (TextUtils.isEmpty(authorCs)) {
            if (TextUtils.isEmpty(titleCs)) {
                authorCs = getString(R.string.AudioUnknownArtist);
                titleCs = " - " + getString(R.string.AudioUnknownTitle);
            } else {
                authorCs = "";
            }
        } else if (!TextUtils.isEmpty(titleCs)) {
            titleCs = " - " + titleCs;
        } else {
            titleCs = "";
        }
        setText(authorCs, titleCs);
    }

    public static CharSequence getTitle(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                String title = attribute.title;
                if (title == null || title.length() == 0) {
                    title = FileLoader.getDocumentFileName(document);
                }
                return title;
            }
        }
        String fileName = FileLoader.getDocumentFileName(document);
        if (!TextUtils.isEmpty(fileName)) {
            return fileName;
        }
        return null;
    }

    public static CharSequence getAuthor(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                if (!attribute.voice) {
                    return attribute.performer;
                }
            }
        }
        return null;
    }

    public void setText(CharSequence author, CharSequence title) {
        this.rawAuthorText = author == null ? "" : author;
        this.rawTitleText = title == null ? "" : title;
        this.author = new Text(this.rawAuthorText, 11, AndroidUtilities.bold());
        this.title = new Text(this.rawTitleText, 11);
        this.classicAuthor = new Text(this.rawAuthorText, 13.5f, AndroidUtilities.bold());
        this.classicTitle = new Text(this.rawTitleText, 13.5f);

        setContentDescription(getString(R.string.AccDescrProfileMusic) + " " + this.rawAuthorText + " — " + this.rawTitleText);
        // Песня профиля приходит позже самой шапки: перерисовываем сразу.
        invalidate();
    }

    private ProfileActivity.AvatarImageView avatarView;
    private boolean ignoreRect = false;
    private RenderNode renderNode;
    private float renderNodeScale;
    private float renderNodeTranslateY;

    public void drawingBlur(boolean drawing) {
        if (trailingView != null) trailingView.drawingBlur(drawing);
        if (ignoreRect != drawing || renderNode != null) {
            ignoreRect = drawing;
            renderNode = null;
            avatarView = null;
            invalidate();
        }
    }

    public void drawingBlur(RenderNode renderNode, ProfileActivity.AvatarImageView avatarView, float scale, float dy) {
        if (trailingView != null) trailingView.drawingBlur(renderNode, avatarView, scale, dy);
        this.ignoreRect = false;
        this.renderNode = renderNode;
        this.avatarView = avatarView;
        this.renderNodeScale = scale;
        this.renderNodeTranslateY = dy;
        invalidate();
    }

    private float currentHeight;

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (trailingView != null) trailingView.setAlpha(alpha);
    }

    protected boolean isOnProfilePhoto() {
        return parentExpanded >= 0.8f;
    }

    public void updatePosition(float y, float newHeight) {
        final boolean classic = isClassicBarMode();
        if (classic != lastMeasuredClassic) {
            checkTextColor();
            requestLayout();
        }
        if (classic && getVisibility() == VISIBLE) {
            if (trailingView != null) {
                if (trailingView.getVisibility() == VISIBLE) {
                    // В классическом режиме музыка занимает полноценную широкую строку сверху,
                    // а пилюля баланса звёзд аккуратно садится ниже по центру без наложений.
                    final float classicSlot = dp(42);
                    currentHeight = Math.min(classicSlot, Math.max(0f, newHeight));
                    setTranslationY(y - dp(8));
                    trailingView.updatePosition(y + classicSlot, Math.max(0f, newHeight - classicSlot));
                } else {
                    trailingView.updatePosition(y, 0f);
                    currentHeight = newHeight;
                    setTranslationY(y - dp(8));
                }
            } else {
                currentHeight = newHeight;
                setTranslationY(y - dp(8));
            }
            invalidate();
            return;
        }
        if (trailingView != null) trailingView.updatePosition(y, newHeight);
        currentHeight = newHeight;
        setTranslationY(y - dp(12));
        invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        final float minH = isClassicBarMode() ? dp(28) : dp(21);
        final float alpha = Utilities.clamp01(currentHeight / minH);
        if (alpha <= 0) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            bounce.setPressed(rect.contains(event.getX(), event.getY()));
        } else if (event.getAction() == MotionEvent.ACTION_MOVE && bounce.isPressed()) {
            if (!rect.contains(event.getX(), event.getY())) {
                bounce.setPressed(false);
            }
        } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            bounce.setPressed(false);
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (bounce.isPressed()) {
                performClick();
            }
            bounce.setPressed(false);
        }
        return bounce.isPressed();
    }

    protected float getContentWidth(float maxWidth) {
        final float textWidth = Math.max(0, maxWidth - dp(16) - dp(16.6f) - dp(8));
        this.author.ellipsize(textWidth * 0.55f);
        if (this.title.getCurrentWidth() > 0 && textWidth - this.author.getWidth() < dp(48)) {
            this.author.ellipsize(Math.max(0, textWidth - dp(48)));
        }
        this.title.ellipsize(Math.max(0, textWidth - this.author.getWidth()));
        return dp(16.6f) + this.author.getWidth() + this.title.getWidth() + dp(8);
    }

    protected void drawContent(Canvas canvas, float alpha) {
        final float cy = getHeight() / 2f;

        final int iconSz = dp(13);
        icon.setBounds(0, (int) cy - iconSz / 2, iconSz, (int) cy + iconSz / 2);
        icon.draw(canvas);

        canvas.translate(dp(16.6f), 0);
        this.author.draw(canvas, 0, cy, textColor, alpha);
        canvas.translate(this.author.getWidth(), 0);
        this.title.draw(canvas, 0, cy, textColor, 0.85f * alpha);
        canvas.translate(this.title.getWidth(), 0);

        arrowPaint.setStrokeWidth(dpf2(1.16f));
        canvas.translate(dpf2(4.8f), cy);
        canvas.drawPath(arrowPath, arrowPaint);
    }

    private boolean hasVisibleCompanion() {
        if (isClassicBarMode()) {
            return false;
        }
        if (leadingView != null && leadingView.isClassicBarMode()) {
            return false;
        }
        ProfileMusicView companion = trailingView != null ? trailingView : leadingView;
        return companion != null && companion.getVisibility() == VISIBLE;
    }

    private float getPillWidth(float maxWidth) {
        return dp(16) + getContentWidth(maxWidth);
    }

    private boolean isCurrentlyPlayingTrack() {
        if (currentDocument == null) {
            return false;
        }
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null || MediaController.getInstance().isMessagePaused()) {
            return false;
        }
        final TLRPC.Document doc = playing.getDocument();
        return doc != null && doc.id != 0 && doc.id == currentDocument.id;
    }

    private void drawClassicBar(@NonNull Canvas canvas) {
        if (classicAuthor == null || classicTitle == null) {
            return;
        }
        final float alpha = Utilities.clamp01(currentHeight / dp(28));
        if (alpha <= 0f) {
            return;
        }
        if (classicBgPaint.getColor() == 0) {
            setColor(lastPeerColor);
        }
        final float scale = bounce.getScale(0.02f);
        final float hPad = dp(14);
        final float barH = dp(34) * alpha;
        final float top = (getHeight() - barH) / 2f;
        final float corner = dp(11);
        rect.set(hPad, top, getWidth() - hPad, top + barH);

        canvas.save();
        canvas.scale(scale, scale, rect.centerX(), rect.centerY());

        final boolean blurActive = !ignoreRect && renderNode != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && renderNode.hasDisplayList() && canvas.isHardwareAccelerated();

        if (!blurActive && classicWithShadows && SharedConfig.shadowsInSections && parentExpanded < 0.5f) {
            classicBgPaint.setShadowLayer(dpf2(2f), 0, dpf2(0.5f), multAlpha(0x16000000, alpha));
            classicStrokePaint.setStyle(Paint.Style.STROKE);
            classicStrokePaint.setStrokeWidth(dpf2(0.66f));
            classicStrokePaint.setColor(multAlpha(Theme.isCurrentThemeDark() ? 0x18FFFFFF : 0x10000000, alpha));
        } else {
            classicBgPaint.setShadowLayer(0, 0, 0, 0);
            classicStrokePaint.setColor(0);
        }

        if (!blurActive || parentExpanded < 0.8f) {
            final int savedAlpha = classicBgPaint.getAlpha();
            classicBgPaint.setAlpha((int) (savedAlpha * alpha * (blurActive ? (1f - parentExpanded * 0.7f) : 1f)));
            canvas.drawRoundRect(rect, corner, corner, classicBgPaint);
            classicBgPaint.setAlpha(savedAlpha);
            if (classicStrokePaint.getColor() != 0) {
                canvas.drawRoundRect(rect, corner, corner, classicStrokePaint);
            }
        }

        clipPath.rewind();
        clipPath.addRoundRect(rect, corner, corner, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);

        if (blurActive) {
            canvas.save();
            if (avatarView != null) {
                View v = (View) avatarView.getParent();
                float vl = v.getX() - getX();
                float vt = v.getY() - getTranslationY();
                float vw = v.getWidth() * v.getScaleX();
                float vh = v.getHeight() * v.getScaleY();
                clipAvatarPath.rewind();
                clipAvatarPath.addRoundRect(
                    vl, vt, vl + vw, vt + vh,
                    avatarView.getRoundRadiusForExpand() * v.getScaleX(),
                    avatarView.getRoundRadiusForExpand() * v.getScaleY(),
                    Path.Direction.CCW
                );
                canvas.clipPath(clipAvatarPath);
            }
            canvas.translate(0f, renderNodeTranslateY);
            canvas.scale(renderNodeScale, renderNodeScale);
            canvas.drawRenderNode(renderNode);
            canvas.restore();
            if (parentExpanded >= 0.8f) {
                badgePaint.setColor(multAlpha(0x36000000, alpha));
                canvas.drawRect(rect, badgePaint);
            }
        }

        final float cy = rect.centerY();
        final float badgeSize = dp(22);
        final float badgeLeft = rect.left + dp(8);
        final float badgeTop = cy - badgeSize / 2f;
        badgeRect.set(badgeLeft, badgeTop, badgeLeft + badgeSize, badgeTop + badgeSize);

        badgePaint.setColor(multAlpha(textColor, 0.13f * alpha));
        canvas.drawRoundRect(badgeRect, dp(6), dp(6), badgePaint);
        if (hasCoverThumb) {
            coverReceiver.setImageCoords(badgeRect.left, badgeRect.top, badgeRect.width(), badgeRect.height());
            coverReceiver.setAlpha(alpha);
            coverReceiver.draw(canvas);
        } else {
            final int iconSz = dp(13);
            icon.setAlpha((int) (255 * alpha));
            icon.setBounds(
                (int) (badgeRect.centerX() - iconSz / 2f),
                (int) (badgeRect.centerY() - iconSz / 2f),
                (int) (badgeRect.centerX() + iconSz / 2f),
                (int) (badgeRect.centerY() + iconSz / 2f)
            );
            icon.draw(canvas);
            icon.setAlpha(255);
        }

        final boolean playingNow = isCurrentlyPlayingTrack();
        final float rightReserve = dp(playingNow ? 34 : 20);
        final float textLeft = badgeRect.right + dp(9);
        final float availText = Math.max(0f, (rect.right - rightReserve) - textLeft);

        classicAuthor.ellipsize(availText * 0.52f);
        if (classicTitle.getCurrentWidth() > 0 && availText - classicAuthor.getWidth() < dp(64)) {
            classicAuthor.ellipsize(Math.max(0f, availText - dp(64)));
        }
        classicTitle.ellipsize(Math.max(0f, availText - classicAuthor.getWidth()));

        classicAuthor.draw(canvas, textLeft, cy, textColor, alpha);
        classicTitle.draw(canvas, textLeft + classicAuthor.getWidth(), cy, textColor, 0.82f * alpha);

        if (playingNow) {
            final long t = SystemClock.uptimeMillis();
            final float eqBaseY = cy + dp(5);
            final float eqRight = rect.right - dp(20);
            iconPaint.setColor(multAlpha(textColor, 0.9f * alpha));
            for (int b = 0; b < 3; b++) {
                final float phase = (t * 0.0085f) + b * 1.35f;
                final float h = dp(3) + dp(7) * (0.5f + 0.5f * (float) Math.sin(phase));
                final float bx = eqRight - (2 - b) * dp(3.6f);
                canvas.drawRoundRect(bx, eqBaseY - h, bx + dp(2.2f), eqBaseY, dp(1), dp(1), iconPaint);
            }
            postInvalidateDelayed(32);
        }

        arrowPaint.setStrokeWidth(dpf2(1.35f));
        arrowPaint.setColor(multAlpha(textColor, 0.78f * alpha));
        canvas.save();
        canvas.translate(rect.right - dp(12), cy);
        canvas.drawPath(arrowPath, arrowPaint);
        canvas.restore();

        canvas.restore();
        canvas.restore();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (isClassicBarMode()) {
            checkTextColor();
            drawClassicBar(canvas);
            return;
        }
        if (this.author == null || this.title == null) return;

        final float alpha = Utilities.clamp01((currentHeight) / dp(21));
        final float scale = bounce.getScale(0.02f);
        if (alpha <= 0) return;

        final int padding = dp(12);
        final int maxWidth = getWidth() - padding * 2;

        float containerWidth;
        float left;
        if (hasVisibleCompanion()) {
            ProfileMusicView leading = leadingView != null ? leadingView : this;
            ProfileMusicView trailing = trailingView != null ? trailingView : this;
            // Reserve the balance first so long song titles cannot push it offscreen.
            float trailingWidth = trailing.getPillWidth(maxWidth / 2f);
            float leadingWidth = leading.getPillWidth(Math.max(0, maxWidth - trailingWidth - dp(6)));
            left = (getWidth() - leadingWidth - dp(6) - trailingWidth) / 2f;
            containerWidth = leadingView != null ? trailingWidth : leadingWidth;
            if (leadingView != null) left += leadingWidth + dp(6);
        } else {
            containerWidth = getPillWidth(maxWidth);
            left = (getWidth() - containerWidth) / 2f;
        }

        rect.set(left, dp(10), left + containerWidth, dp(10) + dp(17) * alpha);
        canvas.save();
        canvas.scale(scale, scale, rect.centerX(), getHeight() / 2f);

        final boolean withShadows = SharedConfig.shadowsInSections && isButtonColorLight() && parentExpanded < 0.5f;
        final float bgAlpha = hasColorById ? 0.1f : 1f;
        if (withShadows) {
            backgroundPaint.setShadowLayer(dpf2(1.5f), 0, 0, Theme.multAlpha(Color.BLACK & 0x20FFFFFF, alpha * bgAlpha));
        } else {
            backgroundPaint.setShadowLayer(0, 0, 0, 0);
        }
        int wasAlpha = backgroundPaint.getAlpha();
        backgroundPaint.setAlpha((int) (wasAlpha * alpha * bgAlpha));
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, backgroundPaint);
        backgroundPaint.setAlpha(wasAlpha);
        if (hasColorById && buttonColor != 0) {
            if (radialGradient == null || radialGradientWidth != rect.width()) {
                radialGradientWidth = rect.width();
                radialGradient = new RadialGradient(
                    rect.width() / 2f, dp(17) / 2f, rect.width() * 0.65f,
                    Theme.multAlpha(buttonColor, 0.8f), buttonColor,
                    Shader.TileMode.CLAMP
                );
                shaderPaint.setShader(radialGradient);
            }
            matrix.setTranslate(rect.left, rect.top);
            radialGradient.setLocalMatrix(matrix);
            shaderPaint.setAlpha((int) (0xFF * alpha));
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, shaderPaint);
        }

        clipPath.rewind();
        clipPath.addRoundRect(rect, rect.height() / 2f, rect.height() / 2f, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(clipPath);
        if (!ignoreRect && renderNode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && renderNode.hasDisplayList() && canvas.isHardwareAccelerated()) {
            canvas.save();
            if (avatarView != null) {
                View v = (View) avatarView.getParent();
                float vl = v.getX() - getX();
                float vt = v.getY() - getTranslationY();
                float vw = v.getWidth() * v.getScaleX();
                float vh = v.getHeight() * v.getScaleY();

                clipAvatarPath.rewind();
                clipAvatarPath.addRoundRect(
                    vl,
                    vt,
                    vl + vw,
                    vt + vh,
                    avatarView.getRoundRadiusForExpand() * v.getScaleX(),
                    avatarView.getRoundRadiusForExpand() * v.getScaleY(),
                    Path.Direction.CCW
                );
                canvas.clipPath(clipAvatarPath);
            }
            canvas.translate(0f, renderNodeTranslateY);
            canvas.scale(renderNodeScale, renderNodeScale);
            canvas.drawRenderNode(renderNode);
            canvas.restore();
        }

        canvas.translate(rect.left + dp(8), 0);
        drawContent(canvas, alpha);

        canvas.restore();

        canvas.restore();
    }
}
