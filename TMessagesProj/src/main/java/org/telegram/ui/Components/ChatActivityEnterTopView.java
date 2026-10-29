package org.telegram.ui.Components;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

public class ChatActivityEnterTopView extends FrameLayout {

    private View replyView;
    private View replyCloseView;
    private EditView editView;
    private boolean editMode;
    private boolean editSuggestionMode;
    private boolean panelVisible = true;
    private View linkView;
    private boolean linkVisible;
    private int linkBarHeightPx;
    private View calcView;
    private boolean calcVisible;
    private int calcBarHeightPx;
    private Runnable linkLayoutCallback;

    public ChatActivityEnterTopView(Context context) {
        super(context);
    }

    public void addReplyView(View replyView, LayoutParams layoutParams) {
        if (this.replyView == null) {
            addView(this.replyView = replyView, layoutParams);
            syncVisibilityAndMargins();
        }
    }

    @Override
    public void addView(View child, int index, android.view.ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
        // Крестик отмены ответа добавляется в ChatActivity напрямую в ChatActivityEnterTopView.
        if (replyCloseView == null && child != replyView && child != editView && child != linkView && child != calcView
                && child instanceof ImageView) {
            replyCloseView = child;
            syncVisibilityAndMargins();
        }
        if (calcView != null && child != calcView && child != linkView) {
            calcView.bringToFront();
        }
        if (linkView != null && child != linkView) {
            linkView.bringToFront();
        }
    }

    /** Колбэк EnterView: пересчитать высоту слота при смене видимости бара. */
    public void setLinkLayoutCallback(Runnable callback) {
        this.linkLayoutCallback = callback;
    }

    /** Бар «Помощник ссылок» живёт в этом же слоте, прижат к низу (над полем ввода). */
    public void setLinkView(View linkView, LayoutParams layoutParams) {
        if (this.linkView == null && linkView != null) {
            this.linkView = linkView;
            this.linkBarHeightPx = layoutParams != null && layoutParams.height > 0 ? layoutParams.height : 0;
            linkView.setVisibility(GONE);
            addView(linkView, layoutParams);
            linkView.bringToFront();
            syncVisibilityAndMargins();
        }
    }

    /** Бар калькулятора и живого конвертера валют в том же нижнем слоте. */
    public void setCalcView(View calcView, LayoutParams layoutParams) {
        if (this.calcView == null && calcView != null) {
            this.calcView = calcView;
            this.calcBarHeightPx = layoutParams != null && layoutParams.height > 0 ? layoutParams.height : 0;
            calcView.setVisibility(GONE);
            addView(calcView, layoutParams);
            calcView.bringToFront();
            if (linkView != null) {
                linkView.bringToFront();
            }
            syncVisibilityAndMargins();
        }
    }

    public void setCalcVisible(boolean visible) {
        if (this.calcVisible == visible) {
            return;
        }
        this.calcVisible = visible;
        syncVisibilityAndMargins();
    }

    public boolean isCalcVisible() {
        return calcVisible && !linkVisible && calcView != null && calcView.getVisibility() == VISIBLE;
    }

    public void setPanelVisible(boolean visible) {
        if (this.panelVisible == visible) {
            return;
        }
        this.panelVisible = visible;
        syncVisibilityAndMargins();
    }

    public boolean isPanelVisible() {
        return panelVisible;
    }

    public void setLinkVisible(boolean visible) {
        if (this.linkVisible == visible) {
            return;
        }
        this.linkVisible = visible;
        syncVisibilityAndMargins();
    }

    public boolean isLinkVisible() {
        return linkVisible && linkView != null && linkView.getVisibility() == VISIBLE;
    }

    private void applyLinkVisibility() {
        final boolean showLink = linkVisible && !editMode;
        final boolean showCalc = calcVisible && !showLink && !editMode;
        if (linkView != null) {
            linkView.setVisibility(showLink ? VISIBLE : GONE);
            if (showLink) {
                linkView.bringToFront();
            }
        }
        if (calcView != null) {
            calcView.setVisibility(showCalc ? VISIBLE : GONE);
            if (showCalc) {
                calcView.bringToFront();
            }
        }
    }

    private void syncVisibilityAndMargins() {
        final boolean showLink = linkVisible && !editMode;
        final boolean showCalc = calcVisible && !showLink && !editMode;
        applyLinkVisibility();

        if (!panelVisible) {
            if (replyView != null) {
                replyView.setVisibility(GONE);
            }
            if (replyCloseView != null) {
                replyCloseView.setVisibility(GONE);
            }
            if (editView != null) {
                editView.setVisibility(GONE);
            }
        } else {
            if (replyView != null) {
                replyView.setVisibility((editMode && !editSuggestionMode) ? GONE : VISIBLE);
                if (replyView.getLayoutParams() instanceof MarginLayoutParams) {
                    final MarginLayoutParams mlp = (MarginLayoutParams) replyView.getLayoutParams();
                    final int targetBottom = (showLink && linkBarHeightPx > 0)
                            ? linkBarHeightPx
                            : ((showCalc && calcBarHeightPx > 0) ? calcBarHeightPx : 0);
                    if (mlp.bottomMargin != targetBottom) {
                        mlp.bottomMargin = targetBottom;
                        replyView.setLayoutParams(mlp);
                    }
                }
            }
            if (replyCloseView != null) {
                replyCloseView.setVisibility(editMode ? GONE : VISIBLE);
            }
            if (editView != null) {
                editView.setVisibility(editMode ? VISIBLE : GONE);
            }
        }
    }

    public void addEditView(EditView editView, LayoutParams layoutParams) {
        if (this.editView == null) {
            this.editView = editView;
            editView.setVisibility(GONE);
            addView(editView, layoutParams);
            syncVisibilityAndMargins();
        }
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        if (!editMode) {
            this.editSuggestionMode = false;
        }
        syncVisibilityAndMargins();
        if (linkLayoutCallback != null) {
            linkLayoutCallback.run();
        }
    }

    public void setEditSuggestionMode(boolean editMode) {
        this.editSuggestionMode = editMode;
        setEditMode(editMode);
        if (editView != null && editView.buttons[0] != null && editView.buttons[1] != null) {
            editView.buttons[0].setOnlyIconMode(editMode);
            editView.buttons[1].setOnlyIconMode(editMode);
        }
    }

    public boolean isEditMode() {
        return editMode;
    }

    public View getReplyView() {
        return replyView;
    }

    public EditView getEditView() {
        return editView;
    }

    public static class EditView extends LinearLayout {

        private EditViewButton[] buttons = new EditViewButton[2];

        public EditView(Context context) {
            super(context);
        }

        public void addButton(EditViewButton button, LayoutParams layoutParams) {
            final int childCount = getChildCount();
            if (childCount < 2) {
                addView(buttons[childCount] = button, layoutParams);
            }
        }

        public EditViewButton[] getButtons() {
            return buttons;
        }

        public void updateColors() {
            for (EditViewButton button : buttons) {
                button.updateColors();
            }
        }
    }

    public static abstract class EditViewButton extends LinearLayout {

        private ImageView imageView;
        private TextView textView;
        private Space space;
        private boolean editButton;

        public EditViewButton(Context context) {
            super(context);
        }

        public void addImageView(ImageView imageView, LayoutParams layoutParams) {
            if (this.imageView == null) {
                addView(this.imageView = imageView, layoutParams);
            }
        }

        public void addSpaceView(Space space, LayoutParams layoutParams) {
            if (this.space == null) {
                addView(this.space = space, layoutParams);
            }
        }

        public void addTextView(TextView textView, LayoutParams layoutParams) {
            if (this.textView == null) {
                addView(this.textView = textView, layoutParams);
            }
        }

        public ImageView getImageView() {
            return imageView;
        }

        public TextView getTextView() {
            return textView;
        }

        public void setEditButton(boolean editButton) {
            this.editButton = editButton;
        }

        public boolean isEditButton() {
            return editButton;
        }

        public void setOnlyIconMode(boolean onlyIconMode) {
            if (textView != null) {
                textView.setVisibility(onlyIconMode ? GONE : VISIBLE);
            }
            if (space != null) {
                space.setVisibility(onlyIconMode ? GONE : VISIBLE);
            }
        }

        public abstract void updateColors();
    }
}
