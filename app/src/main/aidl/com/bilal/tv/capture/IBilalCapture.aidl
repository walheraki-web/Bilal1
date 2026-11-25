package com.bilal.tv.capture;

interface IBilalCapture {
    // Start internal audio capture; streams PCM to the app over a LocalSocket.
    void startCapture() = 1;

    // Run a fixed set of read-only probes (as the shell user) to discover the
    // receiver's currently-tuned channel; returns the combined raw output.
    String detectChannel() = 2;

    // Shizuku calls destroy() on unbind; it must keep this reserved transaction id.
    void destroy() = 16777114;
}
