package com.gathin.tvhome;

import android.service.dreams.DreamService;
import android.view.View;
import android.widget.ImageView;

/**
 * Screensaver: Jellyfin backdrop carousel in the background with the clock and date in the bottom-left corner.
 */
public class ClockDream extends DreamService {

    private View content;
    private JellyfinCarousel jellyfinCarousel;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(true);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        ImageView backdropA = findViewById(R.id.dreamBackdropA);
        ImageView backdropB = findViewById(R.id.dreamBackdropB);
        jellyfinCarousel = new JellyfinCarousel(this, backdropA, backdropB, true);
        jellyfinCarousel.configure(Config.load(this));
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(900).start();
        if (jellyfinCarousel != null) {
            jellyfinCarousel.configure(Config.load(this));
            jellyfinCarousel.start();
        }
    }

    @Override
    public void onDreamingStopped() {
        if (jellyfinCarousel != null) {
            jellyfinCarousel.stop();
        }
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (jellyfinCarousel != null) {
            jellyfinCarousel.stop();
        }
        super.onDetachedFromWindow();
    }
}
