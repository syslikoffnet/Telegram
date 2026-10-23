package org.telegram.ui.Components.blur3.source;

import android.graphics.Canvas;

import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

public interface BlurredBackgroundSource {
    BlurredBackgroundDrawable createDrawable();

    void draw(Canvas canvas, float left, float top, float right, float bottom);


    default void dispatchOnDrawablesRelativePositionChange() {}

    /**
     * Есть ли у источника уже захваченное содержимое. Пока его нет, drawable
     * не должен рисовать кадр: фон и жидкая линза по пустой сцене дают
     * тёмную вспышку на первом кадре (видно на тёмных обоях).
     */
    default boolean hasContent() { return true; }
}
