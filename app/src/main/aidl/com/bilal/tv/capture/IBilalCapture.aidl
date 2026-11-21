package com.bilal.tv.capture;

interface IBilalCapture {
    // Start internal audio capture; streams PCM to the app over a LocalSocket.
    void startCapture() = 1;

    // Shizuku calls destroy() on unbind; it must keep this reserved transaction id.
    void destroy() = 16777114;
}
