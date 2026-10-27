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
    private EditView editView;
    private boolean editMode;
    private View linkView;
    private boolean linkVisible;
    private Runnable linkLayoutCallback;

    public ChatActivityEnterTopView(Context context) {
        super(context);
    }

    public void addReplyView(View replyView, LayoutParams layoutParams) {
        if (this.replyView == null) {
            addView(this.replyView = replyView, layoutParams);
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
            linkView.setVisibility(GONE);
            addView(linkView, layoutParams);
        }
    }

    public void setLinkVisible(boolean visible) {
        if (this.linkVisible == visible) {
            return;
        }
        this.linkVisible = visible;
        applyLinkVisibility();
    }

    public boolean isLinkVisible() {
        return linkVisible && linkView != null && linkView.getVisibility() == VISIBLE;
    }

    private void applyLinkVisibility() {
        if (linkView == null) {
            return;
        }
        final boolean show = linkVisible && !editMode;
        linkView.setVisibility(show ? VISIBLE : GONE);
    }

    public void addEditView(EditView editView, LayoutParams layoutParams) {
        if (this.editView == null) {
            this.editView = editView;
            editView.setVisibility(GONE);
            addView(editView, layoutParams);
        }
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        replyView.setVisibility(editMode ? GONE : VISIBLE);
        editView.setVisibility(editMode ? VISIBLE : GONE);
        applyLinkVisibility();
        if (linkLayoutCallback != null) {
            linkLayoutCallback.run();
        }
    }

    public void setEditSuggestionMode(boolean editMode) {
        setEditMode(editMode);
        if (editMode) {
            replyView.setVisibility(VISIBLE);
        }
        editView.buttons[0].setOnlyIconMode(editMode);
        editView.buttons[1].setOnlyIconMode(editMode);
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
