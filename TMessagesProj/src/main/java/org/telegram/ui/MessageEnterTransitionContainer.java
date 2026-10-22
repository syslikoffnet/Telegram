package org.telegram.ui;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.messenger.NotificationCenter;

import java.util.ArrayList;

@SuppressLint("ViewConstructor")
public class MessageEnterTransitionContainer extends View {

    private ArrayList<Transition> transitions = new ArrayList<>();
    private final int currentAccount;
    private final ViewGroup parent;

    // Почему: idle callback мог уже выйти из очереди до регистрации нового overlay.
    Runnable hideRunnable = () -> { if (transitions.isEmpty()) setVisibility(View.GONE); };

    public MessageEnterTransitionContainer(ViewGroup parent, int currentAccount) {
        super(parent.getContext());
        this.parent = parent;
        this.currentAccount = currentAccount;
    }

    public interface Transition {
        void onDraw(Canvas canvas);
        default void onRemoved() { }
    }

    void addTransition(Transition transition) {
        transitions.add(transition);
        checkVisibility();
        parent.invalidate();
    }

    void removeTransition(Transition transition) {
        if (transitions.remove(transition)) transition.onRemoved();
        checkVisibility();
        parent.invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        while (!transitions.isEmpty()) removeTransition(transitions.get(transitions.size() - 1));
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (transitions.isEmpty()) {
            return;
        }
        for (int i = transitions.size() - 1; i >= 0; i--) {
            transitions.get(i).onDraw(canvas);
        }
    }

    private void checkVisibility() {
        if (transitions.isEmpty() && getVisibility() != View.GONE) {
            NotificationCenter.getInstance(currentAccount).removeDelayed(hideRunnable);
            NotificationCenter.getInstance(currentAccount).doOnIdle(hideRunnable);
        } else if (!transitions.isEmpty()) {
            // Почему: VISIBLE не означает, что старое отложенное скрытие отменено.
            NotificationCenter.getInstance(currentAccount).removeDelayed(hideRunnable);
            if (getVisibility() != View.VISIBLE) setVisibility(View.VISIBLE);
        }
    }

    public boolean isRunning() {
        return transitions.size() > 0;
    }
}
