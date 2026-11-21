package com.bilal.tv.capture;

interface IBilalCapture {
    // Shizuku calls destroy() on unbind; it must keep this reserved transaction id.
    void destroy() = 16777114;

    // Start internal audio capture; streams PCM to the app over a LocalSocket.
    void startCapture();
}
